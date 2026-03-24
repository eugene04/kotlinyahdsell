import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { db } from "../firebase";

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
