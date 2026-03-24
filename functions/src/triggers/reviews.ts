import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { db } from "../firebase";

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
