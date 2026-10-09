//! Small native core shared by the Android app and the Rust API service.
//! Location collection and OS permissions remain in Android's supported APIs.

/// Rejects invalid coordinates before they are sent to the service.
#[unsafe(no_mangle)]
pub extern "C" fn sentinel_coordinates_valid(latitude: f64, longitude: f64) -> bool {
    latitude.is_finite()
        && longitude.is_finite()
        && (-90.0..=90.0).contains(&latitude)
        && (-180.0..=180.0).contains(&longitude)
}
