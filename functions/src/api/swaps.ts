import { logger } from "firebase-functions";
import { HttpsError } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import { db } from "../firebase";
import { storeNotificationRecord, sendPushNotifications, sendSystemChatMessage } from "../utils/notifications";
import { getSafeMeetupSuggestions } from "../utils/geo";

export async function proposeProductSwap(data: any, userId: string | undefined) {
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

export async function respondToSwap(data: any, userId: string | undefined) {
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
