import axios from "axios";
import { logger } from "firebase-functions";
import { GOOGLE_MAPS_API_KEY } from "../config";

/**
 * Calculates distance between two lat/lon points in kilometers.
 */
export function getDistanceFromLatLonInKm(lat1: number | null, lon1: number | null, lat2: number | null, lon2: number | null): number | null {
    if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null;
    const R = 6371; // km
    const dLat = (lat2 - lat1) * (Math.PI / 180); const dLon = (lon2 - lon1) * (Math.PI / 180);
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1 * (Math.PI / 180)) * Math.cos(lat2 * (Math.PI / 180)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
}

/**
 * Uses Google Places API to find nearby safe meetup spots.
 */
export async function getSafeMeetupSuggestions(latitude: number, longitude: number): Promise<string> {
    const apiKey = GOOGLE_MAPS_API_KEY.value();
    if (!apiKey) { logger.error("Google Maps API Key not available."); return ""; }
    const radius = 5000; const types = "police|library";
    const url = `https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=${latitude},${longitude}&radius=${radius}&type=${types}&key=${apiKey}`;
    try {
        const response = await axios.get(url);
        const { results, status, error_message } = response.data;
        if (status !== "OK") { logger.warn(`Google Places API status: ${status}. Message: ${error_message || 'N/A'}`); return ""; }
        if (!results || results.length === 0) { logger.log("No safe meetup spots found."); return ""; }
        const suggestions = results.slice(0, 3).map((place: any, index: number) => `${index + 1}. ${place.name} (${place.vicinity})`).join("\n");
        return suggestions ? `For your safety, consider meeting at a public location. Nearby suggestions:\n${suggestions}` : "";
    } catch (error: any) {
        if (axios.isAxiosError(error)) { logger.error("Axios error (Places API):", error.response?.status, error.response?.data); }
        else { logger.error("Error (Places API):", error); }
        return "";
    }
}
