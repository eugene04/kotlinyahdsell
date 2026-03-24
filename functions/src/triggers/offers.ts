import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { logger } from "firebase-functions";
import { db } from "../firebase";
import { storeNotificationRecord, sendPushNotifications, sendSystemChatMessage } from "../utils/notifications";

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
