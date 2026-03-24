import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { db } from "../firebase";
import { storeNotificationRecord, sendPushNotifications } from "../utils/notifications";

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
