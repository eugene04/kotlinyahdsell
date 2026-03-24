import * as admin from "firebase-admin";
import { FieldValue, Timestamp, GeoPoint, QuerySnapshot } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { HttpsError } from "firebase-functions/v2/https";
import Stripe from "stripe";
import { db } from "../firebase";
import { STRIPE_SECRET_KEY, LISTING_DURATION_DAYS, DEFAULT_FEES } from "../config";
import { storeNotificationRecord, sendPushNotifications, sendSystemChatMessage } from "../utils/notifications";
import { getSafeMeetupSuggestions } from "../utils/geo";

// --- Helper Functions ---
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

export async function getFees() {
    const fees = await getCurrentFees();
    return { fees };
}

export async function updateFees(data: any, userId: string | undefined) {
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

export async function incrementProductViewCount(data: any) {
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

export async function submitProduct(data: any, userId: string | undefined) {
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
            lastBumpedAt_timestamp: Math.floor(Date.now() / 1000),
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

export async function updateProduct(data: any, userId: string | undefined) {
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

export async function placeBid(data: any, userId: string | undefined) {
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

export async function createPaymentIntent(data: any, userId: string | undefined) {
    const fees = await getCurrentFees();
    const listingFee = fees.listingFeeCents;
    if (listingFee <= 0) {
        logger.log("Listing fee is zero or less, skipping Stripe intent creation.");
        return { clientSecret: null };
    }
    try {
        const stripe = new Stripe(STRIPE_SECRET_KEY.value(), { apiVersion: '2025-09-30.clover' as any }); // Updated API version string fallback or specific
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

export async function markProductAsPaid(data: any, userId: string | undefined) {
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

export async function createPromotionPaymentIntent(data: any, userId: string | undefined) {
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
        const stripe = new Stripe(STRIPE_SECRET_KEY.value(), { apiVersion: '2025-09-30.clover' as any });
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

export async function confirmPromotion(data: any, userId: string | undefined) {
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

export async function respondToOffer(data: any, userId: string | undefined) {
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
                logger.log(`Rejected ${otherOffersSnapshot ? otherOffersSnapshot.size - 1 : 0} other pending offers for product ${productId} in transaction.`);
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

export async function markListingAsSold(data: any, userId: string | undefined) {
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

export async function relistProduct(data: any, userId: string | undefined) {
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

export async function clearAllNotifications(data: any, userId: string | undefined) {
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
