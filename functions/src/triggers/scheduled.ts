import * as admin from "firebase-admin";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { db, bucket } from "../firebase";
import { GRACE_PERIOD_DAYS } from "../config";
import { urlToStoragePath } from "../utils/storage";
import { getSafeMeetupSuggestions } from "../utils/geo";
import { storeNotificationRecord, sendPushNotifications, sendSystemChatMessage } from "../utils/notifications";

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
