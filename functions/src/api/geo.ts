import axios from "axios";
import { logger } from "firebase-functions";
import { HttpsError } from "firebase-functions/v2/https";
import { GOOGLE_MAPS_API_KEY } from "../config";

export async function geocodeAddress(data: any) {
    const { address } = data;
    if (!address) throw new HttpsError("invalid-argument", "An address must be provided.");
    const apiKey = GOOGLE_MAPS_API_KEY.value();
    if (!apiKey) throw new HttpsError("internal", "Maps API key not configured.");
    const url = `https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(address)}&key=${apiKey}`;
    try {
        const response = await axios.get(url);
        const { results, status, error_message } = response.data;
        if (status !== "OK" || !results || results.length === 0) {
            logger.error(`Geocoding failed for address "${address}". Status: ${status}, Message: ${error_message}`);
            throw new HttpsError("not-found", `Could not geocode the provided address. Status: ${status}`);
        }
        const location = results[0].geometry.location;
        return { latitude: location.lat, longitude: location.lng };
    } catch (error) {
        logger.error("Error during geocoding:", error);
        if (error instanceof HttpsError) throw error;
        if (axios.isAxiosError(error)) {
            logger.error("Axios error during geocoding:", error.response?.status, error.response?.data);
            throw new HttpsError("internal", "Network error during geocoding.");
        }
        throw new HttpsError("internal", "An error occurred while trying to geocode the address.");
    }
}
