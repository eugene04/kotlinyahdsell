import * as admin from "firebase-admin";
import { logger } from "firebase-functions";
import { HttpsError } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import { db } from "../firebase";
import { storeNotificationRecord, sendPushNotifications } from "../utils/notifications";

export async function postReview(data: any, userId: string | undefined) {
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
            const reviewerDoc = await db.collection("users").doc(userId).get();
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

export async function requestVerification(data: any, userId: string | undefined) {
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

export async function approveVerificationRequest(data: any, userId: string | undefined) {
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
        throw new HttpsError("internal", "Could not verify user.");
    }
}
