import { onCall, CallableRequest, HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import * as products from "./products";
import * as search from "./search";
import * as ai from "./ai";
import * as swaps from "./swaps";
import * as geo from "./geo";
import * as users from "./userRoutes";

export const publicApi = onCall({
    cors: true,
    maxInstances: 10,
    memory: "512MiB",
    timeoutSeconds: 300,
    region: "us-central1"
}, async (request: CallableRequest) => {
    const { action, data } = request.data;
    const userId = request.auth?.uid;

    if (!action) {
        throw new HttpsError("invalid-argument", "The 'action' parameter is required.");
    }

    logger.log(`Received publicApi call: action='${action}', user='${userId || "anonymous"}'`);

    switch (action) {
        // --- Product Actions ---
        case "submitProduct": return products.submitProduct(data, userId);
        case "updateProduct": return products.updateProduct(data, userId);
        case "incrementProductViewCount": return products.incrementProductViewCount(data);
        case "getFees": return products.getFees();
        case "updateFees": return products.updateFees(data, userId);
        case "placeBid": return products.placeBid(data, userId);
        case "createPaymentIntent": return products.createPaymentIntent(data, userId);
        case "markProductAsPaid": return products.markProductAsPaid(data, userId);
        case "createPromotionPaymentIntent": return products.createPromotionPaymentIntent(data, userId);
        case "confirmPromotion": return products.confirmPromotion(data, userId);
        case "respondToOffer": return products.respondToOffer(data, userId);
        case "markListingAsSold": return products.markListingAsSold(data, userId);
        case "relistProduct": return products.relistProduct(data, userId);
        case "clearAllNotifications": return products.clearAllNotifications(data, userId);

        // --- Search & Discovery Actions ---
        case "getRankedProducts": return search.getRankedProducts(data, userId);
        case "getNearbyProducts": return search.getNearbyProducts(data);
        case "saveSearch": return search.saveSearch(data, userId);
        case "geocodeAddress": return geo.geocodeAddress(data);

        // --- Swap Actions ---
        case "proposeProductSwap": return swaps.proposeProductSwap(data, userId);
        case "respondToSwap": return swaps.respondToSwap(data, userId);

        // --- AI Features ---
        case "visualSearch": return ai.visualSearch(data);
        case "parseSearchQuery": return ai.parseSearchQuery(data);
        case "getListingDetailsFromTitle": return ai.getListingDetailsFromTitle(data);
        case "askGemini": return ai.askGemini(data);

        // --- User Actions ---
        case "postReview": return users.postReview(data, userId);
        case "requestVerification": return users.requestVerification(data, userId);
        case "approveVerificationRequest": return users.approveVerificationRequest(data, userId);

        default:
            logger.warn(`Unknown action called: ${action}`);
            throw new HttpsError("invalid-argument", `Unknown action: ${action}`);
    }
});
