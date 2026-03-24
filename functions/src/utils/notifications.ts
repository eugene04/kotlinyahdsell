import * as admin from "firebase-admin";
import { FieldValue } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { db } from "../firebase";

/**
 * Stores a notification record in the user's subcollection.
 */
export async function storeNotificationRecord(recipientId: string, notificationPayload: { title: string; body: string; type: string; data: any; }) {
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
export async function sendPushNotifications(userId: string, payload: { title: string; body: string; data: any; }) {
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
export async function sendSystemChatMessage(uid1: string, uid2: string, messageText: string) {
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
