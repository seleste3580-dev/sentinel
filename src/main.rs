use argon2::{
    Argon2, PasswordHash, PasswordHasher, PasswordVerifier,
    password_hash::{SaltString, rand_core::OsRng},
};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post, put},
};
use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode, decode_header, jwk::JwkSet};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{Row, SqlitePool};
use std::{
    env,
    net::SocketAddr,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::RwLock;
use uuid::Uuid;

#[derive(Clone)]
struct AppState {
    db: SqlitePool,
    http: reqwest::Client,
    google_client_id: Option<String>,
    google_keys: Arc<RwLock<Option<CachedGoogleKeys>>>,
}

#[derive(Clone)]
struct CachedGoogleKeys {
    fetched_at: Instant,
    keys: Arc<JwkSet>,
}

fn token_digest(token: &str) -> String {
    format!("{:x}", Sha256::digest(token.as_bytes()))
}

#[derive(Deserialize)]
struct Credentials {
    email: String,
    password: String,
}

#[derive(Serialize)]
struct Session {
    token: String,
}

#[derive(Serialize)]
struct GoogleNonce {
    nonce: String,
}

#[derive(Deserialize)]
struct GoogleLoginInput {
    id_token: String,
    nonce: String,
}

#[derive(Deserialize)]
struct GoogleClaims {
    sub: String,
    email: String,
    email_verified: bool,
    nonce: String,
}

#[derive(Deserialize)]
struct NewDevice {
    name: String,
}

#[derive(Deserialize)]
struct Location {
    latitude: f64,
    longitude: f64,
    accuracy_m: Option<f64>,
}

#[derive(Serialize)]
struct Device {
    id: String,
    name: String,
    latitude: Option<f64>,
    longitude: Option<f64>,
    accuracy_m: Option<f64>,
    last_seen: Option<String>,
    is_lost: bool,
}

#[derive(Serialize)]
struct ErrorBody {
    error: &'static str,
}

fn error(status: StatusCode, message: &'static str) -> (StatusCode, Json<ErrorBody>) {
    (status, Json(ErrorBody { error: message }))
}

fn normalized_email(email: &str) -> Option<String> {
    let value = email.trim().to_lowercase();
    (value.len() <= 254 && value.contains('@') && !value.contains(' ')).then_some(value)
}

async fn owner(
    db: &SqlitePool,
    headers: &HeaderMap,
) -> Result<String, (StatusCode, Json<ErrorBody>)> {
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| error(StatusCode::UNAUTHORIZED, "sign in required"))?;
    let row = sqlx::query("SELECT user_id FROM sessions WHERE token_hash = ? AND created_at > datetime('now', '-30 days')")
        .bind(token_digest(token)).fetch_optional(db).await
        .map_err(|_| error(StatusCode::INTERNAL_SERVER_ERROR, "service error"))?
        .ok_or_else(|| error(StatusCode::UNAUTHORIZED, "session expired"))?;
    Ok(row.get("user_id"))
}

async fn register(State(s): State<AppState>, Json(input): Json<Credentials>) -> impl IntoResponse {
    let Some(email) = normalized_email(&input.email) else {
        return error(StatusCode::BAD_REQUEST, "enter a valid email").into_response();
    };
    if input.password.len() < 10 || input.password.len() > 128 {
        return error(
            StatusCode::BAD_REQUEST,
            "password must be 10–128 characters",
        )
        .into_response();
    }
    let password = input.password.into_bytes();
    let hash = match tokio::task::spawn_blocking(move || {
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(&password, &salt)
            .map(|value| value.to_string())
    })
    .await
    {
        Ok(Ok(hash)) => hash,
        _ => return error(StatusCode::INTERNAL_SERVER_ERROR, "service error").into_response(),
    };
    let user_id = Uuid::new_v4().to_string();
    if sqlx::query("INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)")
        .bind(&user_id)
        .bind(email)
        .bind(hash)
        .execute(&s.db)
        .await
        .is_err()
    {
        return error(StatusCode::CONFLICT, "account already exists").into_response();
    }
    create_session(&s.db, user_id).await
}

async fn login(State(s): State<AppState>, Json(input): Json<Credentials>) -> impl IntoResponse {
    let Some(email) = normalized_email(&input.email) else {
        return error(StatusCode::BAD_REQUEST, "enter a valid email").into_response();
    };
    let row = match sqlx::query("SELECT id, password_hash FROM users WHERE email = ?")
        .bind(email)
        .fetch_optional(&s.db)
        .await
    {
        Ok(Some(row)) => row,
        _ => {
            return error(StatusCode::UNAUTHORIZED, "email or password is incorrect")
                .into_response();
        }
    };
    let hash: String = row.get("password_hash");
    let password = input.password.into_bytes();
    let valid = tokio::task::spawn_blocking(move || {
        PasswordHash::new(&hash).ok().is_some_and(|parsed| {
            Argon2::default()
                .verify_password(&password, &parsed)
                .is_ok()
        })
    })
    .await
    .unwrap_or(false);
    if !valid {
        return error(StatusCode::UNAUTHORIZED, "email or password is incorrect").into_response();
    }
    create_session(&s.db, row.get("id")).await
}

async fn google_nonce(State(s): State<AppState>) -> impl IntoResponse {
    let nonce = Uuid::new_v4().simple().to_string();
    if sqlx::query("DELETE FROM google_nonces WHERE created_at <= datetime('now', '-5 minutes')")
        .execute(&s.db)
        .await
        .is_err()
        || sqlx::query("INSERT INTO google_nonces (nonce_hash) VALUES (?)")
            .bind(token_digest(&nonce))
            .execute(&s.db)
            .await
            .is_err()
    {
        return error(StatusCode::INTERNAL_SERVER_ERROR, "service error").into_response();
    }
    (StatusCode::OK, Json(GoogleNonce { nonce })).into_response()
}

async fn google_keys(s: &AppState, force_refresh: bool) -> Result<Arc<JwkSet>, ()> {
    {
        let cached = s.google_keys.read().await;
        if !force_refresh {
            if let Some(value) = cached
                .as_ref()
                .filter(|v| v.fetched_at.elapsed() < Duration::from_secs(3600))
            {
                return Ok(value.keys.clone());
            }
        }
    }

    let keys = s
        .http
        .get("https://www.googleapis.com/oauth2/v3/certs")
        .send()
        .await
        .map_err(|_| ())?
        .error_for_status()
        .map_err(|_| ())?
        .json::<JwkSet>()
        .await
        .map_err(|_| ())?;
    let keys = Arc::new(keys);
    *s.google_keys.write().await = Some(CachedGoogleKeys {
        fetched_at: Instant::now(),
        keys: keys.clone(),
    });
    Ok(keys)
}

async fn verify_google_id_token(s: &AppState, id_token: &str) -> Result<GoogleClaims, StatusCode> {
    let header = decode_header(id_token).map_err(|_| StatusCode::UNAUTHORIZED)?;
    if header.alg != Algorithm::RS256 {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let kid = header.kid.ok_or(StatusCode::UNAUTHORIZED)?;
    let keys = google_keys(s, false)
        .await
        .map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?;
    let key = if let Some(jwk) = keys.find(&kid) {
        DecodingKey::from_jwk(jwk).map_err(|_| StatusCode::UNAUTHORIZED)?
    } else {
        let refreshed = google_keys(s, true)
            .await
            .map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?;
        DecodingKey::from_jwk(refreshed.find(&kid).ok_or(StatusCode::UNAUTHORIZED)?)
            .map_err(|_| StatusCode::UNAUTHORIZED)?
    };
    let client_id = s
        .google_client_id
        .as_deref()
        .ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
    let mut validation = Validation::new(Algorithm::RS256);
    validation.set_audience(&[client_id]);
    validation.set_issuer(&["https://accounts.google.com", "accounts.google.com"]);
    validation
        .required_spec_claims
        .extend(["aud".to_owned(), "iss".to_owned(), "sub".to_owned()]);
    let claims = decode::<GoogleClaims>(id_token, &key, &validation)
        .map_err(|_| StatusCode::UNAUTHORIZED)?
        .claims;
    if claims.sub.is_empty() || !claims.email_verified {
        return Err(StatusCode::UNAUTHORIZED);
    }
    Ok(claims)
}

async fn google_login(
    State(s): State<AppState>,
    Json(input): Json<GoogleLoginInput>,
) -> impl IntoResponse {
    if s.google_client_id.is_none() {
        return error(
            StatusCode::SERVICE_UNAVAILABLE,
            "Google sign-in is not configured on the service",
        )
        .into_response();
    }
    let claims = match verify_google_id_token(&s, &input.id_token).await {
        Ok(value) if value.nonce == input.nonce => value,
        Ok(_) => {
            return error(
                StatusCode::UNAUTHORIZED,
                "Google sign-in could not be verified",
            )
            .into_response();
        }
        Err(StatusCode::SERVICE_UNAVAILABLE) => {
            return error(
                StatusCode::SERVICE_UNAVAILABLE,
                "Google sign-in is temporarily unavailable",
            )
            .into_response();
        }
        Err(_) => {
            return error(
                StatusCode::UNAUTHORIZED,
                "Google sign-in could not be verified",
            )
            .into_response();
        }
    };
    let Some(email) = normalized_email(&claims.email) else {
        return error(
            StatusCode::UNAUTHORIZED,
            "Google account has no verified email",
        )
        .into_response();
    };
    match sqlx::query("DELETE FROM google_nonces WHERE nonce_hash = ? AND created_at > datetime('now', '-5 minutes')")
        .bind(token_digest(&input.nonce)).execute(&s.db).await {
        Ok(result) if result.rows_affected() == 1 => {},
        _ => return error(StatusCode::UNAUTHORIZED, "Google sign-in request expired; try again").into_response(),
    }

    let user_id = match sqlx::query("SELECT user_id FROM google_accounts WHERE google_sub = ?")
        .bind(&claims.sub)
        .fetch_optional(&s.db)
        .await
    {
        Ok(Some(row)) => row.get::<String, _>("user_id"),
        Ok(None) => {
            let existing = match sqlx::query("SELECT id FROM users WHERE email = ?")
                .bind(&email)
                .fetch_optional(&s.db)
                .await
            {
                Ok(row) => row,
                Err(_) => {
                    return error(StatusCode::INTERNAL_SERVER_ERROR, "service error")
                        .into_response();
                }
            };
            let id = if let Some(row) = existing {
                row.get::<String, _>("id")
            } else {
                let id = Uuid::new_v4().to_string();
                if sqlx::query(
                    "INSERT INTO users (id, email, password_hash) VALUES (?, ?, '!google-only')",
                )
                .bind(&id)
                .bind(&email)
                .execute(&s.db)
                .await
                .is_err()
                {
                    return error(
                        StatusCode::CONFLICT,
                        "account could not be created; try again",
                    )
                    .into_response();
                }
                id
            };
            if sqlx::query("INSERT INTO google_accounts (google_sub, user_id) VALUES (?, ?)")
                .bind(&claims.sub)
                .bind(&id)
                .execute(&s.db)
                .await
                .is_err()
            {
                return error(
                    StatusCode::CONFLICT,
                    "Google account could not be linked; try again",
                )
                .into_response();
            }
            id
        }
        Err(_) => return error(StatusCode::INTERNAL_SERVER_ERROR, "service error").into_response(),
    };
    create_session(&s.db, user_id).await
}

async fn logout(State(s): State<AppState>, headers: HeaderMap) -> impl IntoResponse {
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));
    if let Some(token) = token {
        let _ = sqlx::query("DELETE FROM sessions WHERE token_hash = ?")
            .bind(token_digest(token))
            .execute(&s.db)
            .await;
    }
    StatusCode::NO_CONTENT
}

async fn create_session(db: &SqlitePool, user_id: String) -> axum::response::Response {
    let token = Uuid::new_v4().to_string();
    if sqlx::query("INSERT INTO sessions (token_hash, user_id) VALUES (?, ?)")
        .bind(token_digest(&token))
        .bind(user_id)
        .execute(db)
        .await
        .is_err()
    {
        return error(StatusCode::INTERNAL_SERVER_ERROR, "service error").into_response();
    }
    (StatusCode::OK, Json(Session { token })).into_response()
}

async fn add_device(
    State(s): State<AppState>,
    headers: HeaderMap,
    Json(input): Json<NewDevice>,
) -> impl IntoResponse {
    let user = match owner(&s.db, &headers).await {
        Ok(user) => user,
        Err(e) => return e.into_response(),
    };
    let name = input.name.trim();
    if name.is_empty() || name.len() > 64 {
        return error(
            StatusCode::BAD_REQUEST,
            "device name must be 1–64 characters",
        )
        .into_response();
    }
    let id = Uuid::new_v4().to_string();
    if sqlx::query("INSERT INTO devices (id, user_id, name, is_lost) VALUES (?, ?, ?, 0)")
        .bind(&id)
        .bind(user)
        .bind(name)
        .execute(&s.db)
        .await
        .is_err()
    {
        return error(StatusCode::INTERNAL_SERVER_ERROR, "service error").into_response();
    }
    (
        StatusCode::CREATED,
        Json(serde_json::json!({"id": id, "name": name})),
    )
        .into_response()
}

async fn list_devices(State(s): State<AppState>, headers: HeaderMap) -> impl IntoResponse {
    let user = match owner(&s.db, &headers).await {
        Ok(user) => user,
        Err(e) => return e.into_response(),
    };
    let rows = match sqlx::query("SELECT id, name, latitude, longitude, accuracy_m, last_seen, is_lost FROM devices WHERE user_id = ? ORDER BY name")
        .bind(user).fetch_all(&s.db).await { Ok(rows) => rows, Err(_) => return error(StatusCode::INTERNAL_SERVER_ERROR, "service error").into_response() };
    let devices: Vec<Device> = rows
        .into_iter()
        .map(|row| Device {
            id: row.get("id"),
            name: row.get("name"),
            latitude: row.get("latitude"),
            longitude: row.get("longitude"),
            accuracy_m: row.get("accuracy_m"),
            last_seen: row.get("last_seen"),
            is_lost: row.get::<i64, _>("is_lost") != 0,
        })
        .collect();
    Json(devices).into_response()
}

async fn put_location(
    State(s): State<AppState>,
    Path(id): Path<String>,
    headers: HeaderMap,
    Json(point): Json<Location>,
) -> impl IntoResponse {
    let user = match owner(&s.db, &headers).await {
        Ok(user) => user,
        Err(e) => return e.into_response(),
    };
    if !sentinel_core::sentinel_coordinates_valid(point.latitude, point.longitude) {
        return error(StatusCode::BAD_REQUEST, "invalid coordinates").into_response();
    }
    if point
        .accuracy_m
        .is_some_and(|accuracy| !accuracy.is_finite() || accuracy < 0.0)
    {
        return error(StatusCode::BAD_REQUEST, "invalid accuracy").into_response();
    }
    let result = sqlx::query("UPDATE devices SET latitude = ?, longitude = ?, accuracy_m = ?, last_seen = datetime('now') WHERE id = ? AND user_id = ?")
        .bind(point.latitude).bind(point.longitude).bind(point.accuracy_m).bind(id).bind(user).execute(&s.db).await;
    match result {
        Ok(r) if r.rows_affected() == 1 => StatusCode::NO_CONTENT.into_response(),
        Ok(_) => error(StatusCode::NOT_FOUND, "device not found").into_response(),
        Err(_) => error(StatusCode::INTERNAL_SERVER_ERROR, "service error").into_response(),
    }
}

async fn set_lost(
    State(s): State<AppState>,
    Path(id): Path<String>,
    headers: HeaderMap,
    Json(body): Json<serde_json::Value>,
) -> impl IntoResponse {
    let user = match owner(&s.db, &headers).await {
        Ok(user) => user,
        Err(e) => return e.into_response(),
    };
    let lost = match body.get("is_lost").and_then(serde_json::Value::as_bool) {
        Some(v) => v,
        None => {
            return error(StatusCode::BAD_REQUEST, "is_lost must be true or false").into_response();
        }
    };
    match sqlx::query("UPDATE devices SET is_lost = ? WHERE id = ? AND user_id = ?")
        .bind(lost)
        .bind(id)
        .bind(user)
        .execute(&s.db)
        .await
    {
        Ok(r) if r.rows_affected() == 1 => StatusCode::NO_CONTENT.into_response(),
        Ok(_) => error(StatusCode::NOT_FOUND, "device not found").into_response(),
        Err(_) => error(StatusCode::INTERNAL_SERVER_ERROR, "service error").into_response(),
    }
}

async fn health() -> &'static str {
    "ok"
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let database_url =
        env::var("DATABASE_URL").unwrap_or_else(|_| "sqlite://sentinel.db?mode=rwc".to_string());
    let db = SqlitePool::connect(&database_url).await?;
    sqlx::query("CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL);").execute(&db).await?;
    sqlx::query("CREATE TABLE IF NOT EXISTS sessions (token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL DEFAULT (datetime('now')));").execute(&db).await?;
    sqlx::query("CREATE TABLE IF NOT EXISTS google_accounts (google_sub TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id));").execute(&db).await?;
    sqlx::query("CREATE TABLE IF NOT EXISTS google_nonces (nonce_hash TEXT PRIMARY KEY, created_at TEXT NOT NULL DEFAULT (datetime('now')));").execute(&db).await?;
    sqlx::query("CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL, latitude REAL, longitude REAL, accuracy_m REAL, last_seen TEXT, is_lost INTEGER NOT NULL DEFAULT 0);").execute(&db).await?;
    sqlx::query("DELETE FROM sessions WHERE created_at <= datetime('now', '-30 days')")
        .execute(&db)
        .await?;
    let google_client_id = env::var("GOOGLE_CLIENT_ID")
        .ok()
        .filter(|id| !id.trim().is_empty());
    let state = AppState {
        db,
        http: reqwest::Client::builder()
            .timeout(Duration::from_secs(8))
            .build()?,
        google_client_id,
        google_keys: Arc::new(RwLock::new(None)),
    };
    let app = Router::new()
        .route("/health", get(health))
        .route("/v1/register", post(register))
        .route("/v1/login", post(login))
        .route("/v1/google/nonce", post(google_nonce))
        .route("/v1/google/login", post(google_login))
        .route("/v1/logout", post(logout))
        .route("/v1/devices", get(list_devices).post(add_device))
        .route("/v1/devices/{id}/location", put(put_location))
        .route("/v1/devices/{id}/lost", post(set_lost))
        .layer(DefaultBodyLimit::max(32 * 1024))
        .with_state(state);
    let bind_addr = env::var("BIND_ADDR")
        .ok()
        .or_else(|| env::var("PORT").ok().map(|port| format!("0.0.0.0:{port}")))
        .unwrap_or_else(|| "127.0.0.1:8080".into());
    let addr: SocketAddr = bind_addr.parse()?;
    let listener = tokio::net::TcpListener::bind(addr).await?;
    println!("Sentinel API listening on http://{addr}");
    axum::serve(listener, app).await?;
    Ok(())
}
