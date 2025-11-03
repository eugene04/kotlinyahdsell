import {
    GoogleGenerativeAI,

    // --- FIX: Remove non-existent helpers and add SchemaType ---
    SchemaType
} from "@google/generative-ai";
import axios from "axios";
import * as admin from "firebase-admin";
import { FieldValue, getFirestore, Timestamp, GeoPoint, QuerySnapshot } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { logger } from "firebase-functions";
import { defineSecret } from "firebase-functions/params";
import { onDocumentCreated, onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import Stripe from "stripe";

// --- Import Algolia (V5 Style based on research) ---
// --- Import Algolia (V5 Style based on research) ---
import { algoliasearch } from 'algoliasearch';
import type { SearchClient } from 'algoliasearch';

// --- Define Parameters for Secrets ---
const STRIPE_SECRET_KEY = defineSecret("STRIPE_SECRET_KEY");
const GOOGLE_MAPS_API_KEY = defineSecret("GOOGLE_MAPS_API_KEY");
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
// --- Define Algolia Secrets (Configure these in Firebase Secret Manager) ---
const ALGOLIA_APP_ID = defineSecret("ALGOLIA_APP_ID");
const ALGOLIA_ADMIN_KEY = defineSecret("ALGOLIA_ADMIN_KEY");
// --- Define Algolia Index Name ---
const ALGOLIA_INDEX_NAME = "products"; // Ensure this matches your index

// --- Constants ---
const LISTING_DURATION_DAYS = 7;
const GRACE_PERIOD_DAYS = 7;
const PRODUCT_CATEGORIES_FOR_AI = [ "Electronics", "Clothing & Apparel", "Home & Garden", "Furniture", "Vehicles", "Books, Movies & Music", "Collectibles & Art", "Sports & Outdoors", "Toys & Hobbies", "Baby & Kids", "Health & Beauty", "Other" ];
// --- ADDED: Valid conditions for AI parsing ---
const PRODUCT_CONDITIONS_FOR_AI = ["New", "Used - Like New", "Used - Good", "Used - Fair"];


// --- Default Fees ---
const DEFAULT_FEES = {
    listingFeeCents: 700,
    bumpFeeCents: 100,
    featureFeeCents: 500,
};

// Initialize Firebase Admin SDK
admin.initializeApp();
const db = getFirestore();
const bucket = getStorage().bucket();

// --- Initialize Algolia Client ---
let algoliaClient: SearchClient | null = null;
function getAlgoliaClient(): SearchClient {
    if (!algoliaClient) {
        const appId = ALGOLIA_APP_ID.value();
        const adminKey = ALGOLIA_ADMIN_KEY.value();
        if (!appId || !adminKey) {
            logger.error("FATAL: Algolia App ID or Admin Key secret is not configured or accessible.");
            throw new HttpsError("internal", "Algolia configuration secrets are missing.");
        }
        algoliaClient = algoliasearch(appId, adminKey);
    }
    return algoliaClient!; // Non-null assertion
}

// --- Helper Functions ---
/**
 * Retrieves the current fee configuration from Firestore or defaults.
 */
async function getCurrentFees(): Promise<{ listingFeeCents: number; bumpFeeCents: number; featureFeeCents: number }> {
    try {
        const feesDoc = await db.collection("config").doc("fees").get();
        if (feesDoc.exists) {
            const data = feesDoc.data();
            return {
                listingFeeCents: typeof data?.listingFeeCents === 'number' ? data.listingFeeCents : DEFAULT_FEES.listingFeeCents,
                bumpFeeCents: typeof data?.bumpFeeCents === 'number' ? data.bumpFeeCents : DEFAULT_FEES.bumpFeeCents,
                featureFeeCents: typeof data?.featureFeeCents === 'number' ? data.featureFeeCents : DEFAULT_FEES.featureFeeCents,
            };
        } else {
            logger.warn("Fees document config/fees not found, using default fees.");
            return DEFAULT_FEES;
        }
    } catch (error) {
        logger.error("Error fetching fees from Firestore, using default fees:", error);
        return DEFAULT_FEES;
    }
}
/**
 * Stores a notification record in the user's subcollection.
 */
async function storeNotificationRecord(recipientId: string, notificationPayload: { title: string; body: string; type: string; data: any; }) {
  if (!recipientId) {
    logger.error("Recipient ID is undefined for notification storage.");
    return;
  }
  try {
    const notificationRef = db.collection("users").doc(recipientId).collection("notifications").doc();
    await notificationRef.set({
      ...notificationPayload,
      recipientId: recipientId,
      createdAt: FieldValue.serverTimestamp(),
      isRead: false,
    });
    logger.log(`Stored notification record for ${recipientId}, type: ${notificationPayload.type}`);
  } catch (error) {
    logger.error(`Error storing notification for ${recipientId}:`, error);
  }
}
/**
 * Fetches FCM tokens for a user and sends a push notification.
 */
async function sendPushNotifications(userId: string, payload: { title: string; body: string; data: any; }) {
    if (!userId) {
        logger.error("Cannot send push notification, userId is missing.");
        return;
    }
    const tokensSnapshot = await db.collection("users").doc(userId).collection("pushTokens").get();
    if (tokensSnapshot.empty) {
        logger.log(`No push tokens found for user ${userId}.`);
        return;
    }
    const tokens = tokensSnapshot.docs.map((doc) => doc.data().token).filter(token => token && typeof token === 'string' && !token.startsWith('ExponentPushToken'));
    if (tokens.length === 0) {
        logger.log(`No valid FCM push tokens after filtering for user ${userId}.`);
        return;
    }
    const message = {
        notification: { title: payload.title, body: payload.body },
        data: payload.data, tokens: tokens,
    };
    try {
        const response = await admin.messaging().sendEachForMulticast(message);
        logger.log(`Successfully sent message to ${response.successCount} tokens for user ${userId}.`);
        if (response.failureCount > 0) {
            logger.warn(`Failed to send message to ${response.failureCount} tokens for user ${userId}.`);
            const tokensToRemove: string[] = [];
            response.responses.forEach((resp, idx) => {
                if (!resp.success) {
                    logger.error(`Token failed: ${tokens[idx]}`, resp.error);
                    if (resp.error?.code === 'messaging/invalid-registration-token' ||
                        resp.error?.code === 'messaging/registration-token-not-registered' ||
                        resp.error?.code === 'messaging/invalid-argument') {
                        tokensToRemove.push(tokens[idx]);
                    }
                }
            });
            if (tokensToRemove.length > 0) {
                logger.log(`Attempting to remove ${tokensToRemove.length} invalid tokens for user ${userId}.`);
                const batch = db.batch();
                const tokensToRemoveDocs = tokensSnapshot.docs.filter(doc => tokensToRemove.includes(doc.data().token));
                tokensToRemoveDocs.forEach(doc => { batch.delete(doc.ref); });
                await batch.commit();
                logger.log(`Removed ${tokensToRemoveDocs.length} invalid tokens.`);
            }
        }
    } catch (error) {
        logger.error(`Error sending push notification multicast to ${userId}:`, error);
    }
}
/**
 * Sends a system message within a private chat.
 */
async function sendSystemChatMessage(uid1: string, uid2: string, messageText: string) {
  if (!uid1 || !uid2) { logger.error("Cannot send system message, one or both UIDs are missing.", { uid1, uid2 }); return; }
  const chatId = [uid1, uid2].sort().join("_");
  const chatDocRef = db.collection("privateChats").doc(chatId);
  const messagesCollectionRef = chatDocRef.collection("messages");
  const messageData = { text: messageText, type: "system", senderId: "system", timestamp: FieldValue.serverTimestamp() };
  const chatMetadata = { participantIds: [uid1, uid2], lastMessage: `[System] ${messageText.substring(0, 45)}${messageText.length > 45 ? '...' : ''}`, lastActivity: FieldValue.serverTimestamp() };
  try {
    await messagesCollectionRef.add(messageData);
    await chatDocRef.set(chatMetadata, { merge: true });
    logger.log(`System message sent to chat ${chatId}.`);
  } catch (error) { logger.error(`FATAL: Error sending system message to chat ${chatId}.`, { error }); }
}
/**
 * Calculates distance between two lat/lon points in kilometers.
 */
function getDistanceFromLatLonInKm(lat1: number | null, lon1: number | null, lat2: number | null, lon2: number | null): number | null {
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
async function getSafeMeetupSuggestions(latitude: number, longitude: number): Promise<string> {
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


// --- Main Callable Cloud Function ---
export const publicApi = onCall(
    {
        secrets: [
            GOOGLE_MAPS_API_KEY, GEMINI_API_KEY, STRIPE_SECRET_KEY,
            ALGOLIA_APP_ID, ALGOLIA_ADMIN_KEY // Added Algolia secrets
        ],
        cpu: 1, memory: "512MiB", concurrency: 80, minInstances: 0,
        maxInstances: 20, timeoutSeconds: 60, region: "us-central1",
        enforceAppCheck: false, // Consider enabling later
    },
    async (request: CallableRequest) => {
        const { action, data } = request.data;
        const userId = request.auth?.uid;
        logger.log(`Action called: ${action}`, { userId: userId || "unauthenticated", data: !!data });

        // --- Initialize Algolia Client Lazily ---
        let client: SearchClient;
        // Assign directly within the if block to satisfy TS strict null checks
        if (action === "getNearbyProducts" || action === "getRankedProducts") {
            try {
                client = getAlgoliaClient(); // Use the global helper
            } catch (e) {
                logger.error("Failed to initialize Algolia client for search action:", e);
                throw new HttpsError("internal", "Search service configuration error.");
            }
        }

        switch (action) {

            // --- Fee Management ---
            case "getFees": {
                const fees = await getCurrentFees();
                return { fees };
            }
            case "updateFees": {
                if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                const adminUser = await admin.auth().getUser(userId);
                if (adminUser.customClaims?.['admin'] !== true) throw new HttpsError("permission-denied", "Admin privileges required.");
                const { listingFeeCents, bumpFeeCents, featureFeeCents } = data;
                if (typeof listingFeeCents !== 'number' || listingFeeCents < 0 || typeof bumpFeeCents !== 'number' || bumpFeeCents < 0 || typeof featureFeeCents !== 'number' || featureFeeCents < 0) {
                    throw new HttpsError("invalid-argument", "All fees must be non-negative numbers (in cents).");
                }
                try {
                    const feesRef = db.collection("config").doc("fees");
                    await feesRef.set({
                        listingFeeCents: Math.round(listingFeeCents), bumpFeeCents: Math.round(bumpFeeCents), featureFeeCents: Math.round(featureFeeCents),
                        updatedAt: FieldValue.serverTimestamp(), updatedBy: userId,
                    }, { merge: true });
                    logger.log(`Fees updated by admin ${userId}:`, { listingFeeCents, bumpFeeCents, featureFeeCents });
                    return { success: true, message: "Fees updated successfully." };
                } catch (error) {
                    logger.error(`Error updating fees by admin ${userId}:`, error);
                    throw new HttpsError("internal", "Could not update fees configuration.");
                }
            }

            // --- Product Ranking (Uses Algolia V5 with facetFilters/numericFilters) ---
case "getRankedProducts": {
    if (!client!) { throw new HttpsError("internal", "Search service not initialized."); }
    const { latitude: buyerLat, longitude: buyerLon, query = "", filters = {} } = data as { latitude?: number, longitude?: number, query?: string, filters?: any };
    const hasBuyerLocation = (typeof buyerLat === "number" && typeof buyerLon === "number");
    const nowTimestamp = Math.floor(Date.now() / 1000);

    // ✅ FIX: Use numeric values (1/0) instead of boolean strings (true/false)
    const baseFilters = `isPaid = 1 AND isSold = 0 AND expiresAt_timestamp > ${nowTimestamp}`;
    const facetFilterArray: string[][] = [];
    const numericFilterArray: string[] = [];

    if (filters.category && filters.category !== "All") {
        facetFilterArray.push([`category:${filters.category}`]);
    }
    if (filters.condition && filters.condition !== "Any Condition") {
        facetFilterArray.push([`condition:${filters.condition}`]);
    }
    if (typeof filters.minPrice === 'number') {
        numericFilterArray.push(`price >= ${filters.minPrice}`);
    }
    if (typeof filters.maxPrice === 'number') {
        numericFilterArray.push(`price <= ${filters.maxPrice}`);
    }

    logger.log(`Algolia search query: "${query}", Base Filters: "${baseFilters}", Facet Filters: ${JSON.stringify(facetFilterArray)}, Numeric Filters: ${JSON.stringify(numericFilterArray)}, Location: ${hasBuyerLocation}`);

    try {
        const searchParams: any = {
            query: query,
            hitsPerPage: 50,
            filters: baseFilters,
            facets: ['category', 'condition'] // <-- ADD THIS
        };

        if (facetFilterArray.length > 0) {
            searchParams.facetFilters = facetFilterArray;
        }
        if (numericFilterArray.length > 0) {
            searchParams.numericFilters = numericFilterArray;
        }

        if (hasBuyerLocation) {
            searchParams.aroundLatLng = `${buyerLat},${buyerLon}`;
            searchParams.minimumAroundRadius = 1000;
        }

        const response: any = await client.searchSingleIndex({
            indexName: ALGOLIA_INDEX_NAME,
            searchParams: searchParams,
        });

        // --- Corrected Log Placement ---
        logger.log("Algolia search response FACETS:", JSON.stringify(response.facets || {}));
        // -----------------------------

        const rankedProducts = response.hits.map((hit: any) => {
            let distanceKm: number | null = null;
            if (hasBuyerLocation && hit._geoloc) {
                distanceKm = getDistanceFromLatLonInKm(buyerLat!, buyerLon!, hit._geoloc.lat, hit._geoloc.lng);
            }
            return { id: hit.objectID, distanceKm: distanceKm };
        });
        logger.log(`Returning ${rankedProducts.length} ranked product IDs from Algolia.`);
        return { rankedProducts, facets: response.facets || {} };
    } catch (error: any) {
        logger.error("Error searching Algolia V5 for ranked products:", error.message || error);
        throw new HttpsError("internal", "Failed to retrieve product ranking.");
    }
}

            // --- Geocoding ---
            case "geocodeAddress": {
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

            // --- View Count (Write to Firestore, Synced by Extension) ---
            case "incrementProductViewCount": {
                 const { productId } = data;
                if (!productId) throw new HttpsError("invalid-argument", "A valid productId must be provided.");
                const productRef = db.collection("products").doc(productId);
                try {
                    await productRef.update({ viewCount: FieldValue.increment(1) });
                    return { success: true, message: "View count incremented." };
                } catch (error) {
                    logger.error(`Error incrementing view count for product ${productId}:`, error);
                    return { success: false, message: "Could not update view count." };
                }
            }

            // --- AI (Uses gemini-2.5-flash) ---
            case "getListingDetailsFromTitle": {
                const { title } = data;
                if (!title) throw new HttpsError("invalid-argument", "A valid product title is required.");
                const apiKey = GEMINI_API_KEY.value();
                if (!apiKey) throw new HttpsError("internal", "AI service is not configured correctly.");
                try {
                    const genAI = new GoogleGenerativeAI(apiKey);
                    const model = genAI.getGenerativeModel({ model:"gemini-2.5-flash" });
                    const prompt = `Based on the product title "${title}", generate a compelling product description (2-3 sentences) and suggest the most appropriate category. Respond ONLY with a valid JSON object like this: {"description": "...", "category": "..."}. Do not include markdown formatting like \`\`\`json. Valid Categories are strictly limited to: ${PRODUCT_CATEGORIES_FOR_AI.join(", ")}`;
                    const result = await model.generateContent(prompt);
                    const responseText = result.response.text();
                    let suggestions;
                    try {
                        const jsonMatch = responseText.match(/{[\s\S]*}/);
                        if (jsonMatch) suggestions = JSON.parse(jsonMatch[0]);
                        else throw new Error("No JSON object found in the response.");
                    } catch (parseError) {
                        logger.error("Failed to parse Gemini response as JSON:", responseText, parseError);
                        throw new HttpsError("internal", "Could not process AI suggestions (format error).");
                    }
                    if (!suggestions.category || !PRODUCT_CATEGORIES_FOR_AI.includes(suggestions.category)) {
                        logger.warn(`Gemini suggested invalid or missing category '${suggestions.category}', defaulting to 'Other'. Response: ${responseText}`);
                        suggestions.category = "Other";
                    }
                     if (!suggestions.description) {
                         logger.warn(`Gemini did not provide a description. Response: ${responseText}`);
                         suggestions.description = "";
                    }
                    return { suggestions };
                } catch (error: any) {
                    logger.error("Error in getListingDetailsFromTitle:", error);
                    if (error instanceof HttpsError) throw error;
                     if (error.message && (error.message.includes("quota") || error.message.includes("RESOURCE_EXHAUSTED") || error.message.includes("API key not valid"))) {
                         throw new HttpsError("resource-exhausted", `AI service error: ${error.message}. Please try again later.`);
                    }
                    throw new HttpsError("internal", "Could not generate suggestions due to an internal error.");
                }
            }
            case "visualSearch": {
                 const { imageUrl } = data;
                if (!imageUrl) throw new HttpsError("invalid-argument", "A valid 'imageUrl' is required.");
                const apiKey = GEMINI_API_KEY.value();
                if (!apiKey) throw new HttpsError("internal", "AI service is not configured correctly.");
                try {
                    const imageResponse = await axios.get(imageUrl, { responseType: "arraybuffer" });
                    const base64Image = Buffer.from(imageResponse.data, "binary").toString("base64");
                    const mimeType = imageResponse.headers["content-type"] || "image/jpeg";
                    if (base64Image.length * 0.75 > 4 * 1024 * 1024) throw new HttpsError("invalid-argument", "Image size is too large (max 4MB).");
                    const imagePart = { inlineData: { data: base64Image, mimeType: mimeType } };
                    const prompt = `Analyze this image and identify the main product shown. Respond with a concise search query (2-4 words max) suitable for finding similar items, and suggest the most appropriate category for listing it. Respond ONLY with a valid JSON object like this: {"searchQuery": "...", "category": "..."}. Do not include markdown formatting like \`\`\`json. Valid Categories are strictly limited to: ${PRODUCT_CATEGORIES_FOR_AI.join(", ")}`;
                    const genAI = new GoogleGenerativeAI(apiKey);
                    const model = genAI.getGenerativeModel({ model: "gemini-2.5-flash" });
                    const result = await model.generateContent([prompt, imagePart]);
                    const responseText = result.response.text();
                     let suggestions;
                     try {
                         const jsonMatch = responseText.match(/{[\s\S]*}/);
                         if (jsonMatch) suggestions = JSON.parse(jsonMatch[0]);
                         else throw new Error("No JSON object found in the response.");
                     } catch (parseError) {
                         logger.error("Failed to parse Gemini visual search response as JSON:", responseText, parseError);
                         throw new HttpsError("internal", "Could not process image search results (format error).");
                     }
                     if (!suggestions.category || !PRODUCT_CATEGORIES_FOR_AI.includes(suggestions.category)) {
                         logger.warn(`Gemini suggested invalid or missing category '${suggestions.category}' from image, defaulting to 'Other'. Response: ${responseText}`);
                         suggestions.category = "Other";
                    }
                     if (!suggestions.searchQuery) {
                         logger.warn(`Gemini did not provide a search query. Response: ${responseText}`);
                         suggestions.searchQuery = "";
                    }
                    return { suggestions };
                } catch (error: any) {
                    logger.error("Error in visualSearch:", error);
                    if (error instanceof HttpsError) throw error;
                     if (error.message && (error.message.includes("quota") || error.message.includes("RESOURCE_EXHAUSTED") || error.message.includes("API key not valid"))) {
                         throw new HttpsError("resource-exhausted", `AI service error: ${error.message}. Please try again later.`);
                    }
                     if (axios.isAxiosError(error)) {
                         logger.error("Axios error fetching image for visual search:", error.response?.status, error.response?.data);
                         throw new HttpsError("internal", "Network error fetching image.");
                     }
                    throw new HttpsError("internal", "Could not process the image search due to an internal error.");
                }
            }

            // --- NEW: AI-Powered Conversational Search Parsing ---
            case "parseSearchQuery": {
                const { query } = data;
                if (!query || typeof query !== 'string' || query.trim().length === 0) {
                    throw new HttpsError("invalid-argument", "A non-empty 'query' string is required.");
                }
                const apiKey = GEMINI_API_KEY.value();
                if (!apiKey) {
                    throw new HttpsError("internal", "AI service is not configured correctly.");
                }

                try {
                    const genAI = new GoogleGenerativeAI(apiKey);
                    const model = genAI.getGenerativeModel({ model: "gemini-2.5-flash" });

                    // --- FIX: Revert to manual schema with SchemaType, StringFormat, and 'as const' ---
                    const schema = {
    type: SchemaType.OBJECT,
    properties: {
        searchQuery: {
            type: SchemaType.STRING,
            description: "The main search term or item description, e.g., 'red shirt', 'iPhone 12'.",
        },
        filters: {
            type: SchemaType.OBJECT,
            description: "Extracted filters. Omit any filter that is not mentioned.",
            properties: {
                category: {
                    type: SchemaType.STRING,
                    description: `The single most-likely category. Must be one of: ${PRODUCT_CATEGORIES_FOR_AI.join(", ")}.`,
                    enum: PRODUCT_CATEGORIES_FOR_AI,
                    nullable: true,
                },
                condition: {
                    type: SchemaType.STRING,
                    description: `The item condition. Must be one of: ${PRODUCT_CONDITIONS_FOR_AI.join(", ")}.`,
                    enum: PRODUCT_CONDITIONS_FOR_AI,
                    nullable: true,
                },
                minPrice: {
                    type: SchemaType.NUMBER,
                    description: "The minimum price, e.g., 'over $50' means 50.",
                    nullable: true,
                },
                maxPrice: {
                    type: SchemaType.NUMBER,
                    description: "The maximum price, e.g., 'under $100' means 100.",
                    nullable: true,
                },
            },
        },
    },
    required: ["searchQuery", "filters"],
} as any;

                    const prompt = `Analyze the user's search query: "${query}".
                    Parse it into a structured JSON object according to the provided schema.
                    - "searchQuery" should be the core item (e.g., 'lathe machine', 'blue t-shirt').
                    - "filters" should only contain constraints explicitly mentioned (like price, category, or condition).
                    - If a filter is not mentioned, omit it from the 'filters' object.
                    - For 'category' and 'condition', only use the exact values provided in the schema's 'enum' list.
                    - Extract prices like 'under 50' as 'maxPrice: 50' or 'over 100' as 'minPrice: 100'.
                    - If no specific item is mentioned but filters are, use an empty string for "searchQuery".`;
                    
                    // --- FIX: Added 'role: "user"' to the contents object ---
                    const result = await model.generateContent({
                        contents: [{ role: "user", parts: [{ text: prompt }] }],
                        generationConfig: {
                            responseMimeType: "application/json",
                            responseSchema: schema,
                        },
                    });
                    // ---------------------------------------------------------

                    const responseText = result.response.text();
                    logger.log("Gemini parsed search query response:", responseText);

                    let parsedJson;
                    try {
                        parsedJson = JSON.parse(responseText);
                    } catch (e) {
                        logger.error("Failed to parse Gemini JSON response:", e, responseText);
                        throw new HttpsError("internal", "AI failed to return valid JSON.");
                    }
                    
                    // Return the structured object
                    return { parsedSearch: parsedJson };

                } catch (error: any) {
                    logger.error("Error in parseSearchQuery:", error);
                    if (error.response?.candidates?.[0]?.finishReason) {
                        logger.error("Gemini Finish Reason:", error.response.candidates[0].finishReason);
                    }
                    throw new HttpsError("internal", "Failed to parse search query with AI.");
                }
            }
            // --- END NEW CASE ---

            case "askGemini": {
                const { prompt } = data;
                if (!prompt) throw new HttpsError("invalid-argument", "A non-empty 'prompt' is required.");
                const apiKey = GEMINI_API_KEY.value();
                if (!apiKey) throw new HttpsError("internal", "AI service API key not configured.");
                try {
                    const genAI = new GoogleGenerativeAI(apiKey);
                    const model = genAI.getGenerativeModel({ model:"gemini-2.5-flash" });
                    const result = await model.generateContent(prompt);
                    const response = result.response;
                    const text = response.text();
                    if (!text) {
                        logger.warn("Gemini returned an empty response for prompt:", prompt, "Full response:", JSON.stringify(result.response));
                        throw new HttpsError("internal", "AI model returned an empty response.");
                    }
                    return { reply: text };
                } catch (error: any) {
                    logger.error("Error calling Gemini in askGemini:", error);
                    if (error.message && (error.message.includes("quota") || error.message.includes("RESOURCE_EXHAUSTED") || error.message.includes("API key not valid"))) {
                         throw new HttpsError("resource-exhausted", `AI service error: ${error.message}. Please try again later.`);
                    }
                    throw new HttpsError("internal", "Failed to process request with AI model.");
                }
            }

            // --- Product CUD (Write to Firestore, Synced by Extension, includes numeric timestamps) ---
            case "submitProduct": {
                 const { name, description, price, category, condition, imageUris, videoUri, isAuction, auctionDurationDays, location, itemAddress } = data;
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 if (!name || typeof price !== 'number' || price < 0 || !category || !condition || !imageUris || !Array.isArray(imageUris) || imageUris.length === 0 || !location || typeof location.latitude !== 'number' || typeof location.longitude !== 'number') {
                     throw new HttpsError("invalid-argument", "Missing or invalid required product information.");
                 }
                 try {
                     const sellerDoc = await db.collection("users").doc(userId).get();
                     if (!sellerDoc.exists) throw new HttpsError("not-found", "Seller profile not found.");
                     const sellerData = sellerDoc.data();
                     const now = FieldValue.serverTimestamp();
                     let expiryTimestamp: admin.firestore.Timestamp | null = null;
                     let duration = LISTING_DURATION_DAYS;
                     if (isAuction && typeof auctionDurationDays === 'number' && auctionDurationDays > 0) {
                          const validDurations = [1, 3, 5, 7];
                          duration = validDurations.includes(auctionDurationDays) ? auctionDurationDays : LISTING_DURATION_DAYS;
                     }
                     const expiryDate = new Date();
                     expiryDate.setDate(expiryDate.getDate() + duration);
                     expiryTimestamp = Timestamp.fromDate(expiryDate);
                     const productData: any = {
                         name: name.trim(), description: description?.trim() || "", category, condition,
                         imageUrls: imageUris, videoUrl: videoUri || null,
                         sellerId: userId, sellerDisplayName: sellerData?.displayName || "Anonymous",
                         sellerProfilePicUrl: sellerData?.profilePicUrl || null, sellerIsVerified: sellerData?.isVerified || false,
                         sellerAverageRating: sellerData?.averageRating || 0.0,
                         sellerLocation: new GeoPoint(location.latitude, location.longitude),
                         itemAddress: itemAddress?.trim() || null,
                         isSold: false, isPaid: false, createdAt: now, expiresAt: expiryTimestamp,
                         viewCount: 0, isFeatured: false, lastBumpedAt: null,
                         createdAt_timestamp: Math.floor(Date.now() / 1000), // Approximate for creation
                         expiresAt_timestamp: Math.floor(expiryDate.getTime() / 1000),
                         lastBumpedAt_timestamp: Math.floor(Date.now() / 1000), // <-- ADD THIS
                     };
                     if (isAuction) {
                         productData.auctionInfo = { startingPrice: price, currentBid: null, leadingBidderId: null, endTime: null };
                         productData.price = price;
                         productData.auctionDurationDays = duration;
                     } else {
                         productData.price = price;
                     }
                     const docRef = await db.collection("products").add(productData);
                     const minimalSubmittedData = { name: productData.name, isAuction: isAuction, auctionDurationDays: productData.auctionDurationDays };
                     return { success: true, productId: docRef.id, submittedData: minimalSubmittedData };
                 } catch (error) {
                     logger.error(`Error in submitProduct by user ${userId}:`, error);
                     throw new HttpsError("internal", "Could not create product listing.");
                 }
            }
            case "updateProduct": {
                 const { productId, name, description, price, category, condition, imageUris, videoUri, itemAddress, location } = data;
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 if (!productId || !name || typeof price !== 'number' || price < 0 || !category || !condition || !imageUris || !Array.isArray(imageUris) || imageUris.length === 0 || !location || typeof location.latitude !== 'number' || typeof location.longitude !== 'number') {
                      throw new HttpsError("invalid-argument", "Missing or invalid required product information for update.");
                 }
                 const productRef = db.collection("products").doc(productId);
                 try {
                     await db.runTransaction(async (transaction) => {
                         const productDoc = await transaction.get(productRef);
                         if (!productDoc.exists) throw new HttpsError("not-found", "Product not found.");
                         if (productDoc.data()?.sellerId !== userId) throw new HttpsError("permission-denied", "You are not authorized to edit this product.");
                         const productUpdates: any = {
                             name: name.trim(), description: description?.trim() || "", price, category, condition,
                             imageUrls: imageUris, videoUrl: videoUri || null,
                             itemAddress: itemAddress?.trim() || null,
                             sellerLocation: new GeoPoint(location.latitude, location.longitude),
                             updatedAt_timestamp: Math.floor(Date.now() / 1000),
                         };
                         if (productDoc.data()?.auctionInfo) {
                             productUpdates["auctionInfo.startingPrice"] = price;
                         }
                         transaction.update(productRef, productUpdates);
                     });
                     return { success: true, message: "Product updated successfully." };
                 } catch (error) {
                     logger.error(`Error updating product ${productId} by user ${userId}:`, error);
                     if (error instanceof HttpsError) throw error;
                     throw new HttpsError("internal", "Failed to update product.");
                 }
            }

            // --- Swap Logic (Firestore Writes, Synced by Extension) ---
            case "proposeProductSwap": {
                 const { proposingProductId, targetProductId, cashTopUp } = data;
                if (!userId) throw new HttpsError("unauthenticated", "Authentication required to propose a swap.");
                if (!proposingProductId || !targetProductId) throw new HttpsError("invalid-argument", "Both proposing and target product IDs are required.");
                let transactionResult: { success: boolean; swapId: string; swapData: any; } | null = null;
                try {
                    transactionResult = await db.runTransaction(async (transaction) => {
                        const proposingProductRef = db.collection("products").doc(proposingProductId);
                        const targetProductRef = db.collection("products").doc(targetProductId);
                        const [proposingProductDoc, targetProductDoc] = await transaction.getAll(proposingProductRef, targetProductRef);
                        if (!proposingProductDoc.exists || !targetProductDoc.exists) throw new HttpsError("not-found", "One or both products could not be found.");
                        const proposingProductData = proposingProductDoc.data();
                        const targetProductData = targetProductDoc.data();
                        if (proposingProductData?.sellerId !== userId) throw new HttpsError("permission-denied", "You do not own the product you are proposing to swap.");
                        if (proposingProductData?.isSold || targetProductData?.isSold) throw new HttpsError("failed-precondition", "One or both items are already sold or swapped.");
                        if (proposingProductData?.sellerId === targetProductData?.sellerId) throw new HttpsError("failed-precondition", "You cannot swap with yourself.");
                        if (!targetProductData?.sellerId) throw new HttpsError("internal", "Target product is missing seller information.");
                        const swapRef = db.collection("swaps").doc();
                        const swapData = {
                            proposingUserId: userId, proposingProductId, proposingProductName: proposingProductData?.name || "Item",
                            proposingProductImageUrl: proposingProductData?.imageUrls?.[0] || null, targetUserId: targetProductData.sellerId,
                            targetProductId, targetProductName: targetProductData?.name || "Item", targetProductImageUrl: targetProductData?.imageUrls?.[0] || null,
                            status: "pending", cashTopUp: (typeof cashTopUp === 'number' && cashTopUp > 0) ? cashTopUp : null,
                            proposedAt: FieldValue.serverTimestamp()
                        };
                        transaction.set(swapRef, swapData);
                        return { success: true, swapId: swapRef.id, swapData: swapData };
                    });
                    if (!transactionResult || !transactionResult.success || !transactionResult.swapData) {
                        logger.error("Swap transaction reported success but returned invalid data.", { transactionResult });
                        throw new HttpsError("internal", "Swap proposal failed post-transaction.");
                    }
                    const swap = transactionResult.swapData;
                    let notificationBody = `${swap.proposingProductName || 'Someone'} wants to trade their item for your "${swap.targetProductName}".`;
                    if (swap.cashTopUp) notificationBody += ` and is offering an extra $${swap.cashTopUp.toFixed(2)}.`;
                    const notificationPayload = {
                        title: "New Swap Proposal! 🔄", body: notificationBody, type: "swap_proposal",
                        data: { type: "swap_proposal", swapId: transactionResult.swapId }
                    };
                    await storeNotificationRecord(swap.targetUserId, notificationPayload);
                    await sendPushNotifications(swap.targetUserId, notificationPayload);
                    const systemMessage = `A new swap has been proposed: "${swap.proposingProductName}" for "${swap.targetProductName}"${swap.cashTopUp ? ` with a $${swap.cashTopUp.toFixed(2)} cash offer.` : '.'}`;
                    await sendSystemChatMessage(swap.proposingUserId, swap.targetUserId, systemMessage);
                    return { success: true, swapId: transactionResult.swapId };
                } catch (error) {
                    logger.error(`Error in proposeProductSwap (transaction or side effects) by ${userId || 'unknown user'}:`, error);
                    if (error instanceof HttpsError) throw error;
                    throw new HttpsError("internal", "Could not complete the swap proposal process.");
                }
            }
            case "respondToSwap": {
                 const { swapId, response } = data;
                if (!userId) throw new HttpsError("unauthenticated", "Authentication required to respond to a swap.");
                if (!swapId || !["accepted", "rejected"].includes(response)) throw new HttpsError("invalid-argument", "A valid swapId and response ('accepted' or 'rejected') are required.");
                const swapRef = db.collection("swaps").doc(swapId);
                let transactionResultData: any = null;
                try {
                    await db.runTransaction(async (transaction) => {
                        const swapDoc = await transaction.get(swapRef);
                        if (!swapDoc.exists) throw new HttpsError("not-found", "Swap proposal not found.");
                        const swapData = swapDoc.data();
                        if (swapData?.targetUserId !== userId) throw new HttpsError("permission-denied", "You are not authorized to respond to this swap.");
                        if (swapData?.status !== "pending") throw new HttpsError("failed-precondition", "This swap has already been responded to.");
                        const proposingUserId = swapData?.proposingUserId;
                        const targetUserId = swapData?.targetUserId;
                        const proposingProductId = swapData?.proposingProductId;
                        const targetProductId = swapData?.targetProductId;
                        if (!proposingUserId || !targetUserId || !proposingProductId || !targetProductId) throw new HttpsError("internal", "Swap data is incomplete.");
                        let finalStatus = response;
                        let targetProdLocation = null;
                        if (response === "accepted") {
                            const proposingProductRef = db.collection("products").doc(proposingProductId);
                            const targetProductRef = db.collection("products").doc(targetProductId);
                            const [proposingProdDoc, targetProdDoc] = await transaction.getAll(proposingProductRef, targetProductRef);
                            if (!proposingProdDoc.exists || !targetProdDoc.exists) {
                                finalStatus = "rejected";
                                logger.warn(`Swap ${swapId} rejected implicitly as product ${!proposingProdDoc.exists ? proposingProductId : targetProductId} no longer exists.`);
                            } else if (proposingProdDoc.data()?.isSold || targetProdDoc.data()?.isSold) {
                                finalStatus = "rejected";
                                logger.warn(`Swap ${swapId} rejected implicitly as product ${proposingProdDoc.data()?.isSold ? proposingProductId : targetProductId} was already sold.`);
                            } else {
                                targetProdLocation = targetProdDoc.data()?.sellerLocation;
                            }
                        }
                        transactionResultData = { finalStatus: finalStatus, swapData: swapData, targetProductLocation: targetProdLocation };
                        transaction.update(swapRef, { status: finalStatus });
                        if (finalStatus === "accepted") {
                            const proposingProductRef = db.collection("products").doc(proposingProductId);
                            const targetProductRef = db.collection("products").doc(targetProductId);
                            transaction.update(proposingProductRef, { isSold: true, soldAt: FieldValue.serverTimestamp() });
                            transaction.update(targetProductRef, { isSold: true, soldAt: FieldValue.serverTimestamp() });
                            logger.log(`Swap ${swapId} accepted in transaction. Marked products ${proposingProductId} and ${targetProductId} as sold.`);
                        }
                    });
                     if (!transactionResultData) throw new Error("Transaction data missing after completion.");
                     const { finalStatus, swapData, targetProductLocation } = transactionResultData;
                     const proposingUserId = swapData?.proposingUserId;
                     const targetUserId = swapData?.targetUserId;
                     const proposingProductName = swapData?.proposingProductName || "their item";
                     const targetProductName = swapData?.targetProductName || "your item";
                     const cashTopUp = swapData?.cashTopUp;
                     let systemMessage = "";
                     if (finalStatus === "accepted") {
                         systemMessage = `Swap accepted! Please arrange the exchange for "${proposingProductName}" and "${targetProductName}".`;
                         if (cashTopUp && cashTopUp > 0) systemMessage += ` Remember the agreed cash top-up of $${cashTopUp.toFixed(2)}.`;
                         if (targetProductLocation && typeof targetProductLocation.latitude === 'number' && typeof targetProductLocation.longitude === 'number') {
                             try {
                                 const suggestions = await getSafeMeetupSuggestions(targetProductLocation.latitude, targetProductLocation.longitude);
                                 if (suggestions) systemMessage += `\n\n${suggestions}`;
                             } catch (suggestionError) { logger.error(`Error getting safe meetup suggestions for accepted swap ${swapId}:`, suggestionError); }
                         }
                     } else { systemMessage = `Your swap proposal for "${targetProductName}" was declined.`; }
                     if (proposingUserId && targetUserId) await sendSystemChatMessage(proposingUserId, targetUserId, systemMessage);
                     else logger.error(`Cannot send system chat for swap ${swapId}, missing user IDs.`);
                     const notificationPayload = {
                         title: `Swap Proposal ${finalStatus.charAt(0).toUpperCase() + finalStatus.slice(1)}`,
                         body: `Your proposal to swap "${proposingProductName}" for "${targetProductName}" was ${finalStatus}.`,
                         type: `swap_${finalStatus}`, data: { type: `swap_${finalStatus}`, swapId: swapId }
                     };
                     if (proposingUserId) {
                         await storeNotificationRecord(proposingUserId, notificationPayload);
                         await sendPushNotifications(proposingUserId, notificationPayload);
                     } else logger.error(`Cannot send notification for swap ${swapId}, missing proposer ID.`);
                     return { success: true, status: finalStatus };
                } catch (error) {
                    logger.error(`Error in respondToSwap for swap ${swapId} by ${userId}:`, error);
                    if (error instanceof HttpsError) throw error;
                    throw new HttpsError("internal", "Could not respond to the swap.");
                }
            }

            // --- Auction Bidding (Firestore Writes, Synced by Extension) ---
            case "placeBid": {
                const { productId, amount } = data;
                if (!userId) throw new HttpsError("unauthenticated", "Authentication required to place a bid.");
                if (!productId || typeof amount !== 'number' || amount <= 0) throw new HttpsError("invalid-argument", "A valid product ID and positive bid amount are required.");
                const productRef = db.collection("products").doc(productId);
                let transactionResultData: any = null;
                try {
                    await db.runTransaction(async (transaction) => {
                        const productDoc = await transaction.get(productRef);
                        if (!productDoc.exists) throw new HttpsError("not-found", "Product not found.");
                        const productData = productDoc.data();
                        const auction = productData?.auctionInfo;
                        if (!auction) throw new HttpsError("failed-precondition", "This product is not up for auction.");
                        const auctionEndTime = auction.endTime_timestamp || auction.endTime?.toMillis() / 1000;
                        if (auctionEndTime && auctionEndTime < Date.now() / 1000) {
                             throw new HttpsError("failed-precondition", "This auction has already ended.");
                        }
                        if (productData?.sellerId === userId) throw new HttpsError("failed-precondition", "You cannot bid on your own item.");
                        const currentBid = auction.currentBid || auction.startingPrice;
                        if (amount <= currentBid) throw new HttpsError("failed-precondition", `Your bid must be higher than the current amount of $${currentBid.toFixed(2)}.`);
                        const userDoc = await db.collection("users").doc(userId).get();
                        const bidderName = userDoc.data()?.displayName || "Anonymous Bidder";
                        const previousLeadingBidderId = auction.leadingBidderId;
                        transactionResultData = { bidderName: bidderName, previousLeadingBidderId: previousLeadingBidderId, sellerId: productData?.sellerId, productName: productData?.name };
                        const newAuctionInfo = { ...auction, currentBid: amount, leadingBidderId: userId };
                        transaction.update(productRef, { auctionInfo: newAuctionInfo });
                        const bidRef = productRef.collection("bids").doc();
                        transaction.set(bidRef, { bidderId: userId, bidderName, amount, timestamp: FieldValue.serverTimestamp() });
                    });
                     if (!transactionResultData) throw new Error("Transaction data missing after placing bid.");
                     const { bidderName, previousLeadingBidderId, sellerId, productName } = transactionResultData;
                    if (previousLeadingBidderId && previousLeadingBidderId !== userId) {
                         const outbidNotification = { title: "You've been outbid! 💔", body: `Someone placed a higher bid on "${productName}".`, type: "outbid", data: { type: "outbid", productId } };
                         await storeNotificationRecord(previousLeadingBidderId, outbidNotification);
                         await sendPushNotifications(previousLeadingBidderId, outbidNotification);
                    }
                     const sellerNotification = { title: "New High Bid! 📈", body: `${bidderName} placed a new high bid of $${amount.toFixed(2)} on "${productName}".`, type: "new_high_bid", data: { type: "new_high_bid", productId } };
                     if (sellerId) {
                        await storeNotificationRecord(sellerId, sellerNotification);
                        await sendPushNotifications(sellerId, sellerNotification);
                    }
                    return { success: true, message: "Bid placed successfully." };
                } catch (error) {
                    logger.error(`Error placing bid on ${productId} by ${userId}:`, error);
                    if (error instanceof HttpsError) throw error;
                    throw new HttpsError("internal", "Failed to place bid due to an internal error.");
                }
            }

            // --- Payment/Activation (Firestore Writes, Synced by Extension, includes numeric timestamps) ---
            case "createPaymentIntent": {
                 const fees = await getCurrentFees();
                 const listingFee = fees.listingFeeCents;
                 if (listingFee <= 0) {
                     logger.log("Listing fee is zero or less, skipping Stripe intent creation.");
                     return { clientSecret: null };
                 }
                 try {
                     const stripe = new Stripe(STRIPE_SECRET_KEY.value(), { apiVersion: '2025-09-30.clover' });
                     const paymentIntent = await stripe.paymentIntents.create({
                         amount: listingFee, currency: "usd", automatic_payment_methods: { enabled: true },
                          metadata: { userId: userId || 'unknown', productId: data.productId || 'unknown', charge_type: 'listing_fee' }
                     });
                     return { clientSecret: paymentIntent.client_secret };
                 } catch (error: any) {
                     logger.error("Stripe error in createPaymentIntent:", error);
                      const stripeErrorMessage = error.raw?.message || error.message || "Failed to create payment intent.";
                     throw new HttpsError("internal", stripeErrorMessage);
                 }
            }
             case "markProductAsPaid": {
                const { productId, isAuction, auctionDurationDays } = data;
                if (!productId) throw new HttpsError("invalid-argument", "Product ID is required.");
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 let transactionResultData: any = null;
                try {
                    await db.runTransaction(async (transaction) => {
                        const productRef = db.collection("products").doc(productId);
                        const productDoc = await transaction.get(productRef);
                        if (!productDoc.exists) throw new HttpsError("not-found", "Product not found.");
                        const productData = productDoc.data();
                        if (productData?.sellerId !== userId) throw new HttpsError("permission-denied", "You are not the owner of this product.");
                        if (productData?.isPaid) {
                             logger.warn(`Product ${productId} is already marked paid.`);
                             transactionResultData = { alreadyPaid: true, sellerId: productData?.sellerId };
                             return;
                        }
                        let duration = LISTING_DURATION_DAYS;
                        if (isAuction && typeof auctionDurationDays === 'number' && auctionDurationDays > 0) {
                             duration = productData?.auctionDurationDays || auctionDurationDays;
                        }
                        const expiryDate = new Date();
                        expiryDate.setDate(expiryDate.getDate() + duration);
                        const expiryTimestamp = Timestamp.fromDate(expiryDate);
                        const paidAtTimestamp = FieldValue.serverTimestamp();
                        const paidAtNumeric = Math.floor(Date.now() / 1000);
                         transactionResultData = { alreadyPaid: false, sellerId: productData?.sellerId, productName: productData?.name, duration: duration };
                        const updates: { [key: string]: any } = {
                            isPaid: true, paidAt: paidAtTimestamp, expiresAt: expiryTimestamp,
                            expiresAt_timestamp: Math.floor(expiryDate.getTime() / 1000),
                            paidAt_timestamp: paidAtNumeric,
                        };
                        if (isAuction && productData?.auctionInfo) {
                            updates["auctionInfo.endTime"] = expiryTimestamp;
                            updates["auctionInfo.endTime_timestamp"] = Math.floor(expiryDate.getTime() / 1000);
                        } else if (isAuction) {
                             logger.error(`Attempted to set endTime for product ${productId} which is marked as auction but lacks auctionInfo field.`);
                             updates["auctionInfo"] = {
                                 startingPrice: productData?.price || 0, currentBid: null,
                                 leadingBidderId: null, endTime: expiryTimestamp,
                                 endTime_timestamp: Math.floor(expiryDate.getTime() / 1000),
                             };
                        }
                        transaction.update(productRef, updates);
                    });
                     if (!transactionResultData) throw new Error("Transaction data missing after markProductAsPaid.");
                     if (transactionResultData.alreadyPaid) return { success: true, message: "Listing was already active." };
                     const { sellerId, productName, duration } = transactionResultData;
                    const payload = { title: "Your Listing is Live! ✨", body: `Your item "${productName}" is now listed for ${duration} days.`, type: "listing_live", data: { type: "listing_live", productId } };
                     if (sellerId) {
                        await storeNotificationRecord(sellerId, payload);
                        await sendPushNotifications(sellerId, payload);
                     } else logger.error(`Seller ID missing for product ${productId} during activation notification.`);
                    return { success: true, message: "Product listing activated." };
                } catch (error) {
                    logger.error(`Error in markProductAsPaid for product ${productId}:`, error);
                     if (error instanceof HttpsError) throw error;
                    throw new HttpsError("internal", "Could not update product status.");
                }
            }

            // --- Promotions (Firestore Writes, Synced by Extension, includes numeric timestamps) ---
            case "createPromotionPaymentIntent": {
                 const { promotionType, productId } = data;
                 if (promotionType !== "bump" && promotionType !== "feature") throw new HttpsError("invalid-argument", "A valid promotionType ('bump' or 'feature') is required.");
                 const type = promotionType as 'bump' | 'feature';
                 const fees = await getCurrentFees();
                 const amount = type === 'bump' ? fees.bumpFeeCents : fees.featureFeeCents;
                 if (amount <= 0) {
                     logger.log(`Promotion fee for ${type} is zero or less, skipping Stripe intent creation.`);
                     return { clientSecret: null };
                 }
                 try {
                     const stripe = new Stripe(STRIPE_SECRET_KEY.value(), { apiVersion: '2025-09-30.clover' });
                     const paymentIntent = await stripe.paymentIntents.create({
                         amount: amount, currency: "usd", automatic_payment_methods: { enabled: true },
                         metadata: { userId: userId || 'unknown', productId: productId || 'unknown', promotionType: type, charge_type: 'promotion_fee' }
                     });
                     return { clientSecret: paymentIntent.client_secret };
                 } catch (error: any) {
                     logger.error("Stripe error in createPromotionPaymentIntent:", error);
                      const stripeErrorMessage = error.raw?.message || error.message || "Failed to create promotion payment intent.";
                     throw new HttpsError("internal", stripeErrorMessage);
                 }
            }
            case "confirmPromotion": {
                const { productId, promotionType } = data;
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 if (!productId || !promotionType) throw new HttpsError("invalid-argument", "productId and promotionType are required.");
                 const productRef = db.collection("products").doc(productId);
                 let transactionResultData: any = null;
                 try {
                     const fees = await getCurrentFees();
                     const requiredFee = promotionType === 'bump' ? fees.bumpFeeCents : (promotionType === 'feature' ? fees.featureFeeCents : -1);
                     await db.runTransaction(async (transaction) => {
                         const productDoc = await transaction.get(productRef);
                         if (!productDoc.exists) throw new HttpsError("not-found", "Product not found.");
                         const productData = productDoc.data();
                         if (productData?.sellerId !== userId) throw new HttpsError("permission-denied", "You are not authorized to promote this listing.");
                         const now = new Date();
                         const expiresAtNum = productData?.expiresAt_timestamp || productData?.expiresAt?.toMillis() / 1000;
                         if (!productData?.isPaid || productData?.isSold || (expiresAtNum && expiresAtNum < now.getTime() / 1000)) {
                              throw new HttpsError("failed-precondition", "Only active, non-sold listings can be promoted.");
                         }
                          transactionResultData = { productName: productData?.name, promotionType: promotionType };
                         let updateData: any = {};
                         if (promotionType === "bump") {
                             const bumpTimestamp = FieldValue.serverTimestamp();
                             updateData = {
                                 lastBumpedAt: bumpTimestamp,
                                 lastBumpedAt_timestamp: Math.floor(Date.now() / 1000) // Approximation
                             };
                         } else if (promotionType === "feature") {
                             updateData = { isFeatured: true };
                         } else {
                             throw new HttpsError("invalid-argument", "Invalid promotion type.");
                         }
                         transaction.update(productRef, updateData);
                     });
                     if (!transactionResultData) throw new Error("Transaction data missing after confirmPromotion.");
                      const { productName } = transactionResultData;
                     let notificationBody = "";
                      if (promotionType === "bump") notificationBody = `Your item "${productName}" has been bumped to the top!`;
                      else if (promotionType === "feature") notificationBody = `Your item "${productName}" is now a featured listing!`;
                     const payload = { title: "Listing Promoted! 🚀", body: notificationBody, type: "listing_promoted", data: { type: "listing_promoted", productId } };
                     await storeNotificationRecord(userId, payload);
                     await sendPushNotifications(userId, payload);
                     const message = requiredFee > 0 ? "Promotion applied successfully after payment!" : "Free promotion applied successfully!";
                     return { success: true, message: message };
                 } catch (error) {
                     logger.error(`Error confirming promotion for product ${productId}:`, error);
                     if (error instanceof HttpsError) throw error;
                     throw new HttpsError("internal", "Could not apply promotion.");
                 }
            }

             // --- Offer Response (Firestore Writes, Synced by Extension, includes numeric timestamps) ---
             case "respondToOffer": {
                 const { productId, offerId, status } = data;
                if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                if (!productId || !offerId || !["accepted", "rejected"].includes(status)) throw new HttpsError("invalid-argument", "Missing required fields.");
                const productRef = db.collection("products").doc(productId);
                const offerRef = productRef.collection("offers").doc(offerId);
                let transactionResultData: any = null;
                try {
                     await db.runTransaction(async (transaction) => {
                         const productDoc = await transaction.get(productRef);
                         const offerDoc = await transaction.get(offerRef);
                         if (!productDoc.exists) throw new HttpsError("not-found", "Product not found.");
                         if (!offerDoc.exists) throw new HttpsError("not-found", "Offer not found.");
                         const productData = productDoc.data();
                         const offerData = offerDoc.data();
                         if (productData?.sellerId !== userId) throw new HttpsError("permission-denied", "You are not the seller of this item.");
                         if (offerData?.status !== "pending") throw new HttpsError("failed-precondition", "This offer has already been responded to.");
                         let finalStatus = status;
                         let otherOffersSnapshot: QuerySnapshot | null = null;
                         let targetProdLocation = productData?.sellerLocation;
                         if (status === "accepted") {
                              if (productData?.isSold) {
                                 finalStatus = "rejected";
                                 logger.warn(`Offer ${offerId} rejected implicitly as product ${productId} was already sold.`);
                             } else {
                                 const otherOffersQuery = productRef.collection("offers").where("status", "==", "pending");
                                 otherOffersSnapshot = await transaction.get(otherOffersQuery);
                             }
                         }
                          transactionResultData = { finalStatus: finalStatus, productData: productData, offerData: offerData, targetProductLocation: targetProdLocation };
                         transaction.update(offerRef, { status: finalStatus });
                         if (finalStatus === "accepted") {
                             const soldAtTimestamp = FieldValue.serverTimestamp();
                             const soldAtNumeric = Math.floor(Date.now() / 1000); // Approximation
                             transaction.update(productRef, {
                                 isSold: true, soldAt: soldAtTimestamp,
                                 soldAt_timestamp: soldAtNumeric // Add numeric timestamp
                             });
                             logger.log(`Product ${productId} marked as sold in transaction due to offer ${offerId} acceptance.`);
                             otherOffersSnapshot?.docs.forEach((doc) => {
                                 if (doc.id !== offerId) transaction.update(doc.ref, { status: "rejected" });
                             });
                             logger.log(`Rejected ${otherOffersSnapshot ? otherOffersSnapshot.size -1 : 0} other pending offers for product ${productId} in transaction.`);
                         }
                     });
                     if (!transactionResultData) throw new Error("Transaction data missing after completion.");
                     const { finalStatus, productData, offerData, targetProductLocation } = transactionResultData;
                     const buyerId = offerData?.buyerId;
                     const sellerId = productData?.sellerId;
                     const productName = productData?.name || "your offered item";
                     const offerAmount = offerData?.offerAmount || 0;
                     if (!buyerId || !sellerId) logger.error(`IDs missing for side effects of offer ${offerId} response.`);
                     else {
                         const payload = {
                             title: finalStatus === "accepted" ? `Offer Accepted! 🎉` : `Offer Update for "${productName}"`,
                             body: finalStatus === "accepted" ? `Your offer of $${offerAmount.toFixed(2)} has been accepted.` : `Your offer was declined.`,
                             type: `offer_${finalStatus}`, data: { type: `offer_${finalStatus}`, productId: productId }
                         };
                         await storeNotificationRecord(buyerId, payload);
                         await sendPushNotifications(buyerId, payload);
                         let chatMessage = finalStatus === "accepted" ? `🎉 Your offer for "${productName}" was ACCEPTED!` : `Your offer for "${productName}" was declined.`;
                         if (finalStatus === "accepted" && targetProductLocation && typeof targetProductLocation.latitude === 'number' && typeof targetProductLocation.longitude === 'number') {
                              try {
                                  const suggestions = await getSafeMeetupSuggestions(targetProductLocation.latitude, targetProductLocation.longitude);
                                  if (suggestions) chatMessage += `\n\n${suggestions}`;
                              } catch (suggestionError) { logger.error(`Error getting safe meetup suggestions for accepted offer ${offerId}:`, suggestionError); }
                         }
                         await sendSystemChatMessage(sellerId, buyerId, chatMessage);
                     }
                    return { success: true, message: `Offer ${finalStatus} successfully.` };
                } catch (error) {
                    logger.error(`Error responding to offer ${offerId} for product ${productId}:`, error);
                    if (error instanceof HttpsError) throw error;
                    throw new HttpsError("internal", "An error occurred while responding to the offer.");
                }
            }

            // --- Listing Management (Firestore Writes, Synced by Extension, includes numeric timestamps) ---
            case "markListingAsSold": {
                 const { productId } = data;
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 if (!productId) throw new HttpsError("invalid-argument", "A valid productId must be provided.");
                 const productRef = db.collection("products").doc(productId);
                 try {
                    await db.runTransaction(async (transaction) => {
                         const productDoc = await transaction.get(productRef);
                         if (!productDoc.exists) throw new HttpsError("not-found", "Product does not exist.");
                         const productData = productDoc.data();
                         if (productData?.sellerId !== userId) throw new HttpsError("permission-denied", "You are not authorized to modify this listing.");
                         if (productData?.auctionInfo) throw new HttpsError("failed-precondition", "Auction items are marked sold automatically.");
                          if (productData?.isSold) { logger.warn(`Product ${productId} is already marked as sold.`); return; }
                          const soldAtTimestamp = FieldValue.serverTimestamp();
                          const soldAtNumeric = Math.floor(Date.now() / 1000); // Approximation
                         transaction.update(productRef, {
                             isSold: true, soldAt: soldAtTimestamp,
                             soldAt_timestamp: soldAtNumeric // Add numeric
                         });
                    });
                    return { success: true, message: "Listing marked as sold." };
                 } catch (error) {
                     logger.error(`Error in markListingAsSold for product ${productId}:`, error);
                     if (error instanceof HttpsError) throw error;
                     throw new HttpsError("internal", "An error occurred while marking as sold.");
                 }
            }
            case "relistProduct": {
                 const { productId } = data;
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 if (!productId) throw new HttpsError("invalid-argument", "A valid productId must be provided.");
                 const productRef = db.collection("products").doc(productId);
                 let transactionResultData: any = null;
                 try {
                    await db.runTransaction(async (transaction) => {
                         const productDoc = await transaction.get(productRef);
                         const productData = productDoc.data();
                         if (!productDoc.exists || !productData) throw new HttpsError("not-found", "Product does not exist.");
                         if (productData.sellerId !== userId) throw new HttpsError("permission-denied", "You are not authorized to modify this listing.");
                          if (!productData.isSold) logger.warn(`Relisting product ${productId} which was not marked as sold.`);
                         transactionResultData = { sellerId: productData.sellerId, productName: productData.name };
                         // Reset sold status and potentially soldAt timestamp
                         transaction.update(productRef, {
                             isSold: false, soldAt: null,
                             soldAt_timestamp: null // Also clear numeric timestamp
                         });
                    });
                     if (!transactionResultData) throw new Error("Transaction data missing after relistProduct.");
                      const { sellerId, productName } = transactionResultData;
                     const payload = { title: "Your Item is Relisted!", body: `Your item "${productName}" is now active again. Consider paying again if it expired.`, type: "listing_relisted", data: { type: "listing_relisted", productId } };
                      if (sellerId) {
                         await storeNotificationRecord(sellerId, payload);
                         await sendPushNotifications(sellerId, payload);
                      }
                    return { success: true, message: "Listing has been relisted." };
                 } catch (error) {
                     logger.error(`Error in relistProduct for product ${productId}:`, error);
                     if (error instanceof HttpsError) throw error;
                     throw new HttpsError("internal", "An error occurred while relisting.");
                 }
            }

            // --- User Profile (Firestore Writes, Synced by Extension) ---
             case "postReview": {
                 const { sellerId, rating, comment } = data;
                if (!userId) throw new HttpsError("unauthenticated", "Authentication required to post a review.");
                if (!sellerId || typeof rating !== 'number' || rating < 1 || rating > 5 || !comment || typeof comment !== 'string' || comment.trim().length === 0) throw new HttpsError("invalid-argument", "Missing required fields.");
                if (userId === sellerId) throw new HttpsError("failed-precondition", "You cannot review yourself.");
                let transactionResultData: any = null;
                try {
                    const sellerRef = db.collection("users").doc(sellerId);
                    const reviewRef = db.collection("reviews").doc();
                    await db.runTransaction(async (transaction) => {
                        const sellerDoc = await transaction.get(sellerRef);
                        if (!sellerDoc.exists) throw new HttpsError("not-found", "Seller not found.");
                        const sellerData = sellerDoc.data();
                        const reviewerDoc = await db.collection("users").doc(userId).get(); // Read outside transaction if preferred
                        const reviewerName = reviewerDoc.data()?.displayName || "Anonymous";
                         transactionResultData = { reviewerName: reviewerName, sellerId: sellerId, rating: rating, reviewerId: userId };
                        transaction.set(reviewRef, { sellerId, reviewerId: userId, reviewerName, rating, comment: comment.trim(), createdAt: FieldValue.serverTimestamp() });
                        const currentRatingCount = sellerData?.ratingCount || 0;
                        const currentTotalRating = (sellerData?.averageRating || 0) * currentRatingCount;
                        const newRatingCount = currentRatingCount + 1;
                        const newTotalRating = currentTotalRating + rating;
                        const newAverageRating = Math.round((newTotalRating / newRatingCount) * 10) / 10;
                        transaction.update(sellerRef, { ratingCount: newRatingCount, averageRating: newAverageRating });
                        logger.log(`Review posted by ${userId} for seller ${sellerId}. New rating: ${newAverageRating}`);
                    });
                     if (!transactionResultData) throw new Error("Transaction data missing after postReview.");
                      const { reviewerName, sellerId: notifiedSellerId, rating: reviewRating, reviewerId: notifiedReviewerId } = transactionResultData;
                     const payload = { title: "You received a new review! ⭐", body: `${reviewerName} left you a ${reviewRating}-star review.`, type: "new_review", data: { type: "new_review", sellerId: notifiedSellerId, reviewerId: notifiedReviewerId } };
                      if (notifiedSellerId) {
                        await storeNotificationRecord(notifiedSellerId, payload);
                        await sendPushNotifications(notifiedSellerId, payload);
                      }
                    return { success: true, reviewId: reviewRef.id };
                } catch (error) {
                    logger.error(`Error posting review by ${userId} for ${sellerId}:`, error);
                    if (error instanceof HttpsError) throw error;
                    throw new HttpsError("internal", "Could not submit review.");
                }
             }
             case "requestVerification": {
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 try {
                     const userRef = db.collection("users").doc(userId);
                     await db.runTransaction(async (transaction) => {
                         const userDoc = await transaction.get(userRef);
                         if (!userDoc.exists) throw new HttpsError("not-found", "User profile not found.");
                         if (userDoc.data()?.isVerified) throw new HttpsError("failed-precondition", "User is already verified.");
                         if (userDoc.data()?.verificationRequested) throw new HttpsError("failed-precondition", "Verification already requested.");
                         transaction.update(userRef, { verificationRequested: true });
                     });
                     logger.log(`User ${userId} has requested verification.`);
                     return { success: true, message: "Verification request submitted." };
                 } catch (error) {
                     logger.error(`Error requesting verification for user ${userId}:`, error);
                      if (error instanceof HttpsError) throw error;
                     throw new HttpsError("internal", "Could not submit verification request.");
                 }
             }
             case "approveVerificationRequest": {
                 const { userId: userToVerify } = data;
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required (admin).");
                 if (!userToVerify) throw new HttpsError("invalid-argument", "A valid userId must be provided.");
                 try {
                     const adminUser = await admin.auth().getUser(userId);
                     if (adminUser.customClaims?.['admin'] !== true) throw new HttpsError("permission-denied", "Admin privileges required.");
                     const userRef = db.collection("users").doc(userToVerify);
                     await db.runTransaction(async (transaction) => {
                         const userDoc = await transaction.get(userRef);
                         if (!userDoc.exists) throw new HttpsError("not-found", "User to verify not found.");
                         if (userDoc.data()?.verificationRequested !== true) {
                              logger.warn(`Admin ${userId} attempted to verify user ${userToVerify} who did not request it or was processed.`);
                              throw new HttpsError("failed-precondition", "User did not request verification or was already processed.");
                         }
                         transaction.update(userRef, { isVerified: true, verificationRequested: false });
                     });
                     const notificationPayload = { title: "You're Verified! ✅", body: "Congratulations! Your YahdSell account has been verified.", type: "account_verified", data: { type: "account_verified" } };
                     await storeNotificationRecord(userToVerify, notificationPayload);
                     await sendPushNotifications(userToVerify, notificationPayload);
                     logger.log(`Admin ${userId} has verified user ${userToVerify}.`);
                     return { success: true, message: `User ${userToVerify} has been verified.` };
                 } catch (error) {
                     logger.error(`Error verifying user ${userToVerify} by admin ${userId}:`, error);
                     if (error instanceof HttpsError) throw error;
                     throw new HttpsError("internal", "An error occurred while verifying the user.");
                 }
             }

            // --- Saved Searches (Write to Firestore) ---
            case "saveSearch": {
                 const { query, category, minPrice, maxPrice, condition } = data;
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 if (!query && !category && minPrice == null && maxPrice == null && !condition) throw new HttpsError("invalid-argument", "At least one search criteria is required.");
                 try {
                     const searchRef = db.collection("users").doc(userId).collection("savedSearches").doc();
                     await searchRef.set({
                         userId, query: query || null, category: category || null,
                         minPrice: typeof minPrice === 'number' ? minPrice : null,
                         maxPrice: typeof maxPrice === 'number' ? maxPrice : null,
                         condition: condition || null, createdAt: FieldValue.serverTimestamp()
                     });
                     return { success: true, message: "Search saved successfully!" };
                 } catch (error) {
                     logger.error(`Error saving search for user ${userId}:`, error);
                     throw new HttpsError("internal", "Could not save your search.");
                 }
            }

            // --- Notifications ---
            case "clearAllNotifications": {
                 if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
                 const notificationsRef = db.collection("users").doc(userId).collection("notifications");
                 try {
                     let snapshot = await notificationsRef.limit(500).get();
                     if (snapshot.empty) return { success: true, message: "No notifications to clear." };
                     let deletedCount = 0;
                     while (!snapshot.empty) {
                         const batch = db.batch();
                         snapshot.docs.forEach((doc) => batch.delete(doc.ref));
                         await batch.commit();
                         deletedCount += snapshot.size;
                         if (snapshot.size === 500) snapshot = await notificationsRef.limit(500).get();
                         else break;
                     }
                     logger.log(`Cleared ${deletedCount} notifications for user ${userId}.`);
                     return { success: true, message: "All notifications cleared." };
                 } catch (error) {
                     logger.error(`Error clearing notifications for user ${userId}:`, error);
                     throw new HttpsError("internal", "Could not clear notifications.");
                 }
             }

            // --- Nearby Products (Uses Algolia V5 Geo-Search with facetFilters/numericFilters) ---
case "getNearbyProducts": {
    if (!client!) { throw new HttpsError("internal", "Search service not initialized."); }
    const { latitude, longitude, radiusKm = 10, filters = {} } = data as { latitude?: number, longitude?: number, radiusKm?: number, filters?: any };
    if (typeof latitude !== 'number' || typeof longitude !== 'number') throw new HttpsError("invalid-argument", "Valid latitude and longitude are required.");
    if (typeof radiusKm !== 'number' || radiusKm <= 0) throw new HttpsError("invalid-argument", "Radius must be a positive number in kilometers.");
    const radiusMeters = Math.round(radiusKm * 1000);
    const nowTimestamp = Math.floor(Date.now() / 1000);

    // ✅ FIX: Use numeric values (1/0) instead of boolean strings (true/false)
    const baseFilters = `isPaid = 1 AND isSold = 0 AND expiresAt_timestamp > ${nowTimestamp}`;
    const facetFilterArray: string[][] = [];
    const numericFilterArray: string[] = [];

    if (filters.category && filters.category !== "All") {
        facetFilterArray.push([`category:${filters.category}`]);
    }
    if (filters.condition && filters.condition !== "Any Condition") {
        facetFilterArray.push([`condition:${filters.condition}`]);
    }
    if (typeof filters.minPrice === 'number') {
        numericFilterArray.push(`price >= ${filters.minPrice}`);
    }
    if (typeof filters.maxPrice === 'number') {
        numericFilterArray.push(`price <= ${filters.maxPrice}`);
    }

    logger.log(`Fetching nearby products from Algolia V5 for lat=${latitude}, lon=${longitude}, radius=${radiusKm}km. Base Filters: ${baseFilters}, Facet Filters: ${JSON.stringify(facetFilterArray)}, Numeric Filters: ${JSON.stringify(numericFilterArray)}`);

    try {
        const searchParams: any = {
            query: "",
            aroundLatLng: `${latitude},${longitude}`,
            aroundRadius: radiusMeters,
            minimumAroundRadius: 1000,
            hitsPerPage: 100,
            filters: baseFilters,
            facets: ['category', 'condition'] // <-- ADD THIS
        };

        if (facetFilterArray.length > 0) {
            searchParams.facetFilters = facetFilterArray;
        }
        if (numericFilterArray.length > 0) {
            searchParams.numericFilters = numericFilterArray;
        }

        const response: any = await client.searchSingleIndex({
            indexName: ALGOLIA_INDEX_NAME,
            searchParams: searchParams,
        });
        
        // --- ADDED Log for facets ---
        logger.log("Algolia search response FACETS (Nearby):", JSON.stringify(response.facets || {}));
        // -----------------------------


        const parseTimestampToString = (ts: any): string | null => typeof ts === 'number' ? new Date(ts * 1000).toISOString() : null;

        const nearbyProducts = response.hits.map((hit: any) => {
            let distanceKm: number | null = null;
            if (hit._geoloc) {
                distanceKm = getDistanceFromLatLonInKm(latitude, longitude, hit._geoloc.lat, hit._geoloc.lng);
            }
            return {
                id: hit.objectID, name: hit.name || "", description: hit.description || "",
                price: hit.price || 0.0, category: hit.category || "", condition: hit.condition || "",
                imageUrls: hit.imageUrls || [], videoUrl: hit.videoUrl || null,
                sellerId: hit.sellerId || "", sellerDisplayName: hit.sellerDisplayName || "",
                sellerProfilePicUrl: hit.sellerProfilePicUrl || null, sellerIsVerified: hit.sellerIsVerified || false,
                sellerAverageRating: hit.sellerAverageRating || 0.0,
                sellerLocationLat: hit._geoloc?.lat || null,
                sellerLocationLon: hit._geoloc?.lng || null,
                itemAddress: hit.itemAddress || null, distanceKm: distanceKm,
                isSold: hit.isSold || false, isPaid: hit.isPaid || false, auctionInfo: hit.auctionInfo || null,
                createdAt: parseTimestampToString(hit.createdAt_timestamp),
                paidAt: parseTimestampToString(hit.paidAt_timestamp),
                soldAt: parseTimestampToString(hit.soldAt_timestamp),
                expiresAt: parseTimestampToString(hit.expiresAt_timestamp),
                viewCount: hit.viewCount || 0, isFeatured: hit.isFeatured || false,
                lastBumpedAt: parseTimestampToString(hit.lastBumpedAt_timestamp),
                auctionDurationDays: hit.auctionDurationDays || null
            };
        }).filter((prod: { distanceKm: number | null }) => prod.distanceKm != null && prod.distanceKm <= radiusKm);
        logger.log(`Returning ${nearbyProducts.length} nearby products from Algolia.`);
        return { nearbyProducts: nearbyProducts, facets: response.facets || {} };
    } catch (error: any) {
        logger.error("Error getting nearby products from Algolia V5:", error.message || error);
        throw new HttpsError("internal", "Failed to retrieve nearby products.");
    }
}

            // --- Default Case ---
            default:
                logger.warn("Unknown action called in publicApi:", action);
                throw new HttpsError("not-found", "The requested API action is not valid.");
        }
    }
);

// --- Trigger-based Functions ---
export const sendNewChatMessageNotification = onDocumentCreated("privateChats/{chatId}/messages/{messageId}", async (event) => {
    const messageData = event.data?.data();
    if (!messageData || messageData.senderId === "system") {
      logger.log("No message data or system message, skipping notification.", { chatId: event.params.chatId, messageId: event.params.messageId });
      return;
    }
    const { senderId, text, imageUrl, videoUrl, location } = messageData;
    const chatId = event.params.chatId;
    const participantIds = chatId.split("_");
    const recipientId = participantIds.find((id) => id !== senderId);
    if (!recipientId) { logger.error("Could not determine recipient from chatId:", chatId); return; }
    try {
        const senderDoc = await db.collection("users").doc(senderId).get();
        const senderName = senderDoc.data()?.displayName || "Someone";
        let notificationBody = "Sent you a message.";
        if (text) notificationBody = text;
        else if (imageUrl) notificationBody = "Sent an image.";
        else if (videoUrl) notificationBody = "Sent a video.";
        else if (location) notificationBody = "Shared a location.";
        const payload = {
          title: `New message from ${senderName}`, body: notificationBody, type: "new_chat_message",
          data: { type: "new_chat_message", senderId: senderId, senderName: senderName, chatId: chatId },
        };
        await storeNotificationRecord(recipientId, payload);
        await sendPushNotifications(recipientId, payload);
    } catch (error) { logger.error(`Error processing new chat message notification for chatId ${chatId}:`, error); }
});

export const updateUserProductsOnProfileChange = onDocumentUpdated("users/{userId}", async (event) => {
    const beforeData = event.data?.before.data();
    const afterData = event.data?.after.data();
    if (!beforeData || !afterData) return;
    const nameChanged = beforeData.displayName !== afterData.displayName;
    const picChanged = beforeData.profilePicUrl !== afterData.profilePicUrl;
    const verificationChanged = beforeData.isVerified !== afterData.isVerified;
    const ratingChanged = beforeData.averageRating !== afterData.averageRating;
    if (!nameChanged && !picChanged && !verificationChanged && !ratingChanged) {
        logger.log(`No relevant profile fields changed for user ${event.params.userId}, skipping product update.`);
        return;
    }
    const userId = event.params.userId;
    logger.log(`Profile updated for ${userId}. Updating relevant product fields.`);
    const productUpdates: { [key: string]: any } = {};
    if (nameChanged) productUpdates.sellerDisplayName = afterData.displayName;
    if (picChanged) productUpdates.sellerProfilePicUrl = afterData.profilePicUrl || null;
    if (verificationChanged) productUpdates.sellerIsVerified = afterData.isVerified;
    if (ratingChanged) productUpdates.sellerAverageRating = afterData.averageRating;
    const productsQuery = db.collection("products").where("sellerId", "==", userId);
    try {
      const productSnapshot = await productsQuery.get();
      if (productSnapshot.empty) { logger.log(`User ${userId} has no products to update.`); return; }
      const batchSize = 499; let batch = db.batch(); let count = 0;
      for (const doc of productSnapshot.docs) {
          batch.update(doc.ref, productUpdates); count++;
          if (count === batchSize) { await batch.commit(); batch = db.batch(); count = 0; }
      }
      if (count > 0) { await batch.commit(); }
      logger.log(`Successfully updated ${productSnapshot.size} products for seller ${userId}.`);
    } catch (error) { logger.error(`Error updating products for seller ${userId}:`, error); }
});
export const updateSellerRating = onDocumentWritten("reviews/{reviewId}", async (event) => {
    const beforeData = event.data?.before.data();
    const afterData = event.data?.after.data();
    const sellerId = afterData?.sellerId || beforeData?.sellerId;
    if (!sellerId) { logger.warn(`Could not determine sellerId for review ${event.params.reviewId}.`); return; }
    logger.log(`Review written/deleted for seller ${sellerId}. Recalculating rating.`);
    const sellerRef = db.collection("users").doc(sellerId);
    const reviewsQuery = db.collection("reviews").where("sellerId", "==", sellerId);
    try {
      const reviewsSnapshot = await reviewsQuery.get();
      const ratingCount = reviewsSnapshot.size;
      const totalRatingSum = reviewsSnapshot.docs.reduce((sum, doc) => sum + (doc.data().rating || 0), 0);
      const averageRating = ratingCount > 0 ? Math.round((totalRatingSum / ratingCount) * 10) / 10 : 0;
      await sellerRef.update({ ratingCount, averageRating });
      logger.log(`Updated rating for seller ${sellerId} to ${averageRating} (${ratingCount} reviews).`);
    } catch (error) { logger.error(`Error updating rating for seller ${sellerId}:`, error); }
});
export const sendNewOfferNotificationToSeller = onDocumentCreated("products/{productId}/offers/{offerId}", async (event) => {
    const offerData = event.data?.data();
    if (!offerData) { logger.warn(`No data for new offer ${event.params.offerId}.`); return; }
    const { sellerId, buyerId, buyerName = "Someone", offerAmount = 0 } = offerData;
    const productId = event.params.productId;
    if (!sellerId || !buyerId) { logger.error(`Missing IDs for offer ${event.params.offerId}.`); return; }
    try {
        const productName = (await db.collection("products").doc(productId).get()).data()?.name || "your item";
        const payload = {
            title: `New Offer on "${productName}"`, body: `${buyerName} offered $${offerAmount.toFixed(2)}.`,
            type: "new_offer", data: { type: "new_offer", productId: productId, offerId: event.params.offerId }
        };
        await storeNotificationRecord(sellerId, payload);
        await sendPushNotifications(sellerId, payload);
        await sendSystemChatMessage(sellerId, buyerId, `A new offer of $${offerAmount.toFixed(2)} was made by ${buyerName} for "${productName}".`);
    } catch (error) { logger.error(`Error sending new offer notification for offer ${event.params.offerId}:`, error); }
});

// --- Scheduled Functions ---
export const processEndedAuctions = onSchedule("every 15 minutes", async () => {
    const now = Timestamp.now();
    logger.log("Running scheduled function: processEndedAuctions");
    const nowNumeric = Math.floor(now.toMillis() / 1000);
    const query = db.collection("products")
        .where("auctionInfo.endTime_timestamp", "<=", nowNumeric)
        .where("isSold", "==", false)
        .where("isPaid", "==", true);
    try {
        const snapshot = await query.get();
        if (snapshot.empty) { logger.log("No ended auctions to process."); return; }
        logger.log(`Found ${snapshot.size} ended auctions to process.`);
        const promises = snapshot.docs.map(async (doc) => {
            const product = doc.data();
            const auction = product.auctionInfo;
            const productId = doc.id;
            const sellerId = product.sellerId;
            if (!auction || !sellerId) { logger.error(`Auction info/sellerId missing for auction ${productId}.`); return; }
            if (auction.leadingBidderId) {
                const winnerId = auction.leadingBidderId;
                await doc.ref.update({ isSold: true, soldAt: FieldValue.serverTimestamp() });
                const winnerPayload = { title: "You won the auction! 🏆", body: `Congratulations! You won the auction for "${product.name}".`, type: "auction_win", data: { type: "auction_win", productId: productId } };
                await storeNotificationRecord(winnerId, winnerPayload);
                await sendPushNotifications(winnerId, winnerPayload);
                const sellerPayload = { title: "Your auction has ended!", body: `Your auction for "${product.name}" has ended. Winning bid: $${(auction.currentBid || auction.startingPrice).toFixed(2)}.`, type: "auction_end_winner", data: { type: "auction_end_winner", productId: productId, winnerId: winnerId } };
                await storeNotificationRecord(sellerId, sellerPayload);
                await sendPushNotifications(sellerId, sellerPayload);
                 let systemMessage = `The auction for "${product.name}" has ended. Please arrange payment and collection.`;
                 if (product.sellerLocation && typeof product.sellerLocation.latitude === 'number' && typeof product.sellerLocation.longitude === 'number') {
                     try {
                         const suggestions = await getSafeMeetupSuggestions(product.sellerLocation.latitude, product.sellerLocation.longitude);
                         if (suggestions) systemMessage += `\n\n${suggestions}`;
                     } catch (suggestionError) { logger.error(`Error getting safe meetup suggestions for auction ${productId}:`, suggestionError); }
                 }
                await sendSystemChatMessage(sellerId, winnerId, systemMessage);
                logger.log(`Processed auction win for product ${productId}. Winner: ${winnerId}, Seller: ${sellerId}.`);
                const bidsSnapshot = await db.collection("products").doc(productId).collection("bids").get();
                const allBidders = new Set(bidsSnapshot.docs.map((bidDoc) => bidDoc.data().bidderId));
                allBidders.forEach(async (bidderId) => {
                    if (bidderId !== winnerId && bidderId !== sellerId) {
                        const loserPayload = { title: `Auction ended for "${product.name}"`, body: `The auction has ended. Unfortunately, you were not the highest bidder.`, type: "auction_loss", data: { type: "auction_loss", productId: productId } };
                        await storeNotificationRecord(bidderId, loserPayload);
                        await sendPushNotifications(bidderId, loserPayload);
                        logger.log(`Sent auction loss notification to bidder ${bidderId} for product ${productId}.`);
                    }
                });
            } else {
                const sellerPayload = { title: `Auction ended for "${product.name}"`, body: `Unfortunately, your auction ended without receiving any bids. You might want to relist it.`, type: "auction_end_no_bids", data: { type: "auction_end_no_bids", productId: productId } };
                await storeNotificationRecord(sellerId, sellerPayload);
                await sendPushNotifications(sellerId, sellerPayload);
                logger.log(`Processed ended auction for product ${productId} with no bids.`);
            }
        });
        await Promise.all(promises);
         logger.log(`Finished processing ${snapshot.size} ended auctions.`);
    } catch (error) {
        logger.error("Error processing ended auctions:", error);
    }
});
export const cleanupSoldOrExpiredListings = onSchedule("every 24 hours", async () => {
    const now = new Date();
    const cutoffDate = new Date(now.getTime() - (GRACE_PERIOD_DAYS * 24 * 60 * 60 * 1000));
    logger.log(`Running cleanup for listings expired before ${cutoffDate.toISOString()}.`);
    const cutoffNumeric = Math.floor(cutoffDate.getTime() / 1000);
    const expiredListingsQuery = db.collection("products").where("expiresAt_timestamp", "<", cutoffNumeric);
    try {
        const snapshot = await expiredListingsQuery.get();
        if (snapshot.empty) { logger.log("No expired listings found requiring cleanup."); return; }
        logger.log(`Found ${snapshot.size} expired listings to clean up storage for.`);
        const deletePromises: Promise<any>[] = [];
        const docsToDeleteRefs: admin.firestore.DocumentReference[] = [];
        snapshot.docs.forEach((doc) => {
            const data = doc.data();
            logger.log(`Cleaning storage for expired product ${doc.id}.`);
            const imageStoragePaths: string[] = (data.imageUrls || []).map(urlToStoragePath).filter((p: string | null): p is string => p !== null);
            const videoStoragePath: string | null = data.videoUrl ? urlToStoragePath(data.videoUrl) : null;
            imageStoragePaths.forEach((path) => deletePromises.push(bucket.file(path).delete().catch((e) => logger.error(`Failed to delete image ${path} for ${doc.id}`, e))));
            if (videoStoragePath) deletePromises.push(bucket.file(videoStoragePath).delete().catch((e) => logger.error(`Failed to delete video ${videoStoragePath} for ${doc.id}`, e)));
            // TODO: Delete subcollections (offers, comments, bids) if needed before deleting the main doc.
            docsToDeleteRefs.push(doc.ref);
        });
        await Promise.all(deletePromises);
        logger.log(`Finished storage cleanup attempt for ${snapshot.size} expired listings.`);
        // Delete Firestore documents in batches (triggers Extension to delete from Algolia)
        const batchSize = 499;
        for (let i = 0; i < docsToDeleteRefs.length; i += batchSize) {
            const batch = db.batch();
            const chunk = docsToDeleteRefs.slice(i, i + batchSize);
            chunk.forEach(ref => batch.delete(ref));
            await batch.commit();
            logger.log(`Deleted ${chunk.length} expired product documents from Firestore batch.`);
        }
    } catch (error) { logger.error("Error during cleanup of expired listings:", error); }
});

// --- Keep urlToStoragePath for cleanup function ---
/**
 * Helper to attempt extracting a GCS path from a download URL.
 */
function urlToStoragePath(url: string): string | null {
     try {
         const decodedUrl = decodeURIComponent(url);
         // Match the path part between /o/ and ?alt=media
         const pathRegex = /\/o\/(.*?)\?alt=media/;
         const match = decodedUrl.match(pathRegex);
         if (match && match[1]) {
             // Replace Firebase URL encoding like %2F with /
             return match[1].replace(/%2F/g, '/');
         }
         logger.warn(`Could not extract GCS path from URL format: ${url}`);
         return null;
     } catch (e) {
         logger.error(`Error decoding or parsing storage URL: ${url}`, e);
         return null;
     }
}







