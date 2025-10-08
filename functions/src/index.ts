import { GoogleGenerativeAI } from "@google/generative-ai";
import axios from "axios";
import * as admin from "firebase-admin";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { logger } from "firebase-functions";
import { defineSecret } from "firebase-functions/params";
import { onDocumentCreated, onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import Stripe from "stripe";

// --- Define Parameters for Secrets ---
// ✅ FIXED: Standardized all secrets to use the defineSecret method.
const STRIPE_SECRET_KEY = defineSecret("STRIPE_SECRET_KEY");
const GOOGLE_MAPS_API_KEY = defineSecret("GOOGLE_MAPS_API_KEY");
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");


// --- Constants ---
const LISTING_DURATION_DAYS = 7;
const GRACE_PERIOD_DAYS = 7;
const PRODUCT_CATEGORIES_FOR_AI = [ "Electronics", "Clothing & Apparel", "Home & Garden", "Furniture", "Vehicles", "Books, Movies & Music", "Collectibles & Art", "Sports & Outdoors", "Toys & Hobbies", "Baby & Kids", "Health & Beauty", "Other" ];

// Initialize Firebase Admin SDK
admin.initializeApp();
const db = getFirestore();
const bucket = getStorage().bucket();

// --- Helper Functions (No changes here) ---

async function storeNotificationRecord(recipientId: string, notificationPayload: { title: string; body: string; type: string; data: any; }) {
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
  } catch (error) {
    logger.error(`Error storing notification for ${recipientId}:`, error);
  }
}

async function sendPushNotifications(userId: string, payload: { title: string; body: string; data: any; }) {
    const tokensSnapshot = await db.collection("users").doc(userId).collection("pushTokens").get();
    if (tokensSnapshot.empty) {
        logger.log(`No push tokens found for user ${userId}.`);
        return;
    }
    const tokens = tokensSnapshot.docs.map((doc) => doc.data().token);
    if (tokens.length === 0) return;
    const message = {
        notification: { title: payload.title, body: payload.body },
        data: payload.data,
        tokens: tokens,
    };
    try {
        const response = await admin.messaging().sendEachForMulticast(message);
        logger.log(`Successfully sent message to ${response.successCount} tokens for user ${userId}.`);
        if (response.failureCount > 0) {
            logger.warn(`Failed to send message to ${response.failureCount} tokens for user ${userId}.`);
        }
    } catch (error) {
        logger.error(`Error sending push notification to ${userId}:`, error);
    }
}

async function sendSystemChatMessage(uid1: string, uid2: string, messageText: string) {
  if (!uid1 || !uid2) {
    logger.error("Cannot send system message, one or both UIDs are missing.", { uid1, uid2 });
    return;
  }
  const chatId = [uid1, uid2].sort().join("_");
  const chatDocRef = db.collection("privateChats").doc(chatId);
  const messagesCollectionRef = chatDocRef.collection("messages");
  const messageData = {
    text: messageText,
    type: "system",
    senderId: "system",
    timestamp: FieldValue.serverTimestamp(),
  };
  const chatMetadata = {
    participantIds: [uid1, uid2],
    lastMessage: `[System] ${messageText.substring(0, 45)}`,
    lastActivity: FieldValue.serverTimestamp(),
  };
  try {
    await messagesCollectionRef.add(messageData);
    await chatDocRef.set(chatMetadata, { merge: true });
    logger.log(`System message sent to chat ${chatId}.`);
  } catch (error) {
    logger.error(`FATAL: Error sending system message to chat ${chatId}.`, { error });
  }
}

function getDistanceFromLatLonInKm(lat1: number, lon1: number, lat2: number, lon2: number): number | null {
  if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null;
  const R = 6371; // Radius of the earth in km
  const dLat = (lat2 - lat1) * (Math.PI / 180);
  const dLon = (lon2 - lon1) * (Math.PI / 180);
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1 * (Math.PI / 180)) * Math.cos(lat2 * (Math.PI / 180)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

// --- Consolidated API Function ---

export const api = onCall(
    {
        // ✅ FIXED: Added GEMINI_API_KEY to the list of required secrets.
        secrets: [STRIPE_SECRET_KEY, GOOGLE_MAPS_API_KEY, GEMINI_API_KEY],
        cpu: 1,
        concurrency: 80,
        minInstances: 0,
    },
    async (request: CallableRequest) => {
        const { action, data } = request.data;

        // Helper to check for authentication
        const checkAuth = () => {
            if (!request.auth) {
                throw new HttpsError("unauthenticated", "You must be logged in to perform this action.");
            }
        };

        switch (action) {
            case "geocodeAddress": {
                const { address } = data;
                if (!address) throw new HttpsError("invalid-argument", "An address must be provided.");
                const apiKey = GOOGLE_MAPS_API_KEY.value();
                const url = `https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(address)}&key=${apiKey}`;
                try {
                    const response = await axios.get(url);
                    const { results, status } = response.data;
                    if (status !== "OK" || !results || results.length === 0) throw new HttpsError("not-found", "Could not geocode the provided address.");
                    const location = results[0].geometry.location;
                    return { latitude: location.lat, longitude: location.lng };
                } catch (error) {
                    logger.error("Error during geocoding:", error);
                    throw new HttpsError("internal", "An error occurred while trying to geocode the address.");
                }
            }
            
            case "submitProduct": {
                checkAuth();
                const { name, description, price, category, condition, imageUris, videoUri, isAuction, auctionDurationDays, location, itemAddress } = data;
                const sellerId = request.auth!.uid;
                if (!name || !price || !category || !condition || !imageUris || imageUris.length === 0 || !location) throw new HttpsError("invalid-argument", "Missing required product information.");
                try {
                    const sellerDoc = await db.collection("users").doc(sellerId).get();
                    if (!sellerDoc.exists) throw new HttpsError("not-found", "Seller profile not found.");
                    const sellerData = sellerDoc.data();
                    const productData: any = { name, description, category, condition, imageUrls: imageUris, videoUrl: videoUri || null, sellerId, sellerDisplayName: sellerData?.displayName || "Anonymous", sellerProfilePicUrl: sellerData?.profilePicUrl || null, sellerIsVerified: sellerData?.isVerified || false, sellerAverageRating: sellerData?.averageRating || 0.0, sellerLocation: location, itemAddress: itemAddress || null, isSold: false, isPaid: false, createdAt: FieldValue.serverTimestamp(), viewCount: 0 };
                    if (isAuction) {
                        productData.auctionInfo = { startingPrice: price, currentBid: price, leadingBidderId: null, endTime: null };
                        productData.price = price;
                        productData.auctionDurationDays = auctionDurationDays;
                    } else {
                        productData.price = price;
                    }
                    const docRef = await db.collection("products").add(productData);
                    return { success: true, productId: docRef.id, submittedData: productData };
                } catch (error) {
                    logger.error(`Error in submitProduct by user ${sellerId}:`, error);
                    throw new HttpsError("internal", "Could not create product listing.");
                }
            }

            case "updateProduct": {
                checkAuth();
                const { productId, name, description, price, category, condition, imageUris, videoUri, itemAddress, location } = data;
                const sellerId = request.auth!.uid;
                if (!productId || !name || !price || !category || !condition || !imageUris || imageUris.length === 0 || !location) throw new HttpsError("invalid-argument", "Missing required product information for update.");
                const productRef = db.collection("products").doc(productId);
                try {
                    const productDoc = await productRef.get();
                    if (!productDoc.exists) throw new HttpsError("not-found", "Product not found.");
                    if (productDoc.data()?.sellerId !== sellerId) throw new HttpsError("permission-denied", "You are not authorized to edit this product.");
                    const productUpdates = { name, description, price, category, condition, imageUrls: imageUris, videoUrl: videoUri || null, itemAddress: itemAddress || null, sellerLocation: location };
                    await productRef.update(productUpdates);
                    return { success: true, message: "Product updated successfully." };
                } catch (error) {
                    logger.error(`Error updating product ${productId} by user ${sellerId}:`, error);
                    throw new HttpsError("internal", "Failed to update product.");
                }
            }

            case "incrementProductViewCount": {
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

            case "proposeProductSwap": {
                checkAuth();
                const { proposingProductId, targetProductId, cashTopUp } = data;
                if (!proposingProductId || !targetProductId) throw new HttpsError("invalid-argument", "Both proposing and target product IDs are required.");
                const proposingUserId = request.auth!.uid;
                return db.runTransaction(async (transaction) => {
                    const proposingProductRef = db.collection("products").doc(proposingProductId);
                    const targetProductRef = db.collection("products").doc(targetProductId);
                    const [proposingProductDoc, targetProductDoc] = await transaction.getAll(proposingProductRef, targetProductRef);
                    if (!proposingProductDoc.exists || !targetProductDoc.exists) throw new HttpsError("not-found", "One or both products could not be found.");
                    const proposingProductData = proposingProductDoc.data();
                    const targetProductData = targetProductDoc.data();
                    if (proposingProductData?.sellerId !== proposingUserId) throw new HttpsError("permission-denied", "You do not own the product you are proposing to swap.");
                    if (proposingProductData?.isSold || targetProductData?.isSold) throw new HttpsError("failed-precondition", "One or both items are already sold.");
                    if (proposingProductData?.sellerId === targetProductData?.sellerId) throw new HttpsError("failed-precondition", "You cannot swap with yourself.");
                    const swapRef = db.collection("swaps").doc();
                    const swapData = { proposingUserId, proposingProductId, proposingProductName: proposingProductData?.name, proposingProductImageUrl: proposingProductData?.imageUrls[0] || null, targetUserId: targetProductData?.sellerId, targetProductId, targetProductName: targetProductData?.name, targetProductImageUrl: targetProductData?.imageUrls[0] || null, status: "pending", cashTopUp: cashTopUp > 0 ? cashTopUp : null, proposedAt: FieldValue.serverTimestamp() };
                    transaction.set(swapRef, swapData);
                    let notificationBody = `${proposingProductData?.sellerDisplayName} wants to trade their "${proposingProductData?.name}" for your "${targetProductData?.name}".`;
                    if (cashTopUp > 0) notificationBody += ` and is offering an extra $${cashTopUp.toFixed(2)}.`;
                    const notificationPayload = { title: "New Swap Proposal! 🔄", body: notificationBody, type: "swap_proposal", data: { type: "swap_proposal", swapId: swapRef.id } };
                    await storeNotificationRecord(targetProductData?.sellerId, notificationPayload);
                    await sendPushNotifications(targetProductData?.sellerId, notificationPayload);
                    return { success: true, swapId: swapRef.id };
                });
            }

            case "respondToSwap": {
                checkAuth();
                const { swapId, response } = data;
                if (!swapId || !["accepted", "rejected"].includes(response)) throw new HttpsError("invalid-argument", "A valid swapId and response ('accepted' or 'rejected') are required.");
                const currentUserId = request.auth!.uid;
                const swapRef = db.collection("swaps").doc(swapId);
                return db.runTransaction(async (transaction) => {
                    const swapDoc = await transaction.get(swapRef);
                    if (!swapDoc.exists) throw new HttpsError("not-found", "Swap proposal not found.");
                    const swapData = swapDoc.data();
                    if (swapData?.targetUserId !== currentUserId) throw new HttpsError("permission-denied", "You are not authorized to respond to this swap.");
                    if (swapData?.status !== "pending") throw new HttpsError("failed-precondition", "This swap has already been responded to.");
                    transaction.update(swapRef, { status: response });
                    if (response === "accepted") {
                        const proposingProductRef = db.collection("products").doc(swapData?.proposingProductId);
                        const targetProductRef = db.collection("products").doc(swapData?.targetProductId);
                        transaction.update(proposingProductRef, { isSold: true, soldAt: FieldValue.serverTimestamp() });
                        transaction.update(targetProductRef, { isSold: true, soldAt: FieldValue.serverTimestamp() });
                        if (swapData.cashTopUp > 0) {
                            const message = `Swap accepted! Please arrange the payment of $${swapData.cashTopUp.toFixed(2)} for the trade of "${swapData.proposingProductName}" for "${swapData.targetProductName}".`;
                            await sendSystemChatMessage(swapData.proposingUserId, swapData.targetUserId, message);
                        }
                    }
                    const notificationPayload = { title: `Swap Proposal ${response.charAt(0).toUpperCase() + response.slice(1)}`, body: `Your proposal to swap for "${swapData?.targetProductName}" was ${response}.`, type: `swap_${response}`, data: { type: `swap_${response}`, swapId: swapId } };
                    await storeNotificationRecord(swapData?.proposingUserId, notificationPayload);
                    await sendPushNotifications(swapData?.proposingUserId, notificationPayload);
                    return { success: true, status: response };
                });
            }

            case "placeBid": {
                checkAuth();
                const { productId, amount } = data;
                if (!productId || typeof amount !== "number" || amount <= 0) throw new HttpsError("invalid-argument", "A valid product ID and bid amount are required.");
                const bidderId = request.auth!.uid;
                const productRef = db.collection("products").doc(productId);
                return db.runTransaction(async (transaction) => {
                    const productDoc = await transaction.get(productRef);
                    if (!productDoc.exists) throw new HttpsError("not-found", "Product not found.");
                    const productData = productDoc.data();
                    const auction = productData?.auctionInfo;
                    if (!auction) throw new HttpsError("failed-precondition", "This product is not up for auction.");
                    if (auction.endTime.toDate() < new Date()) throw new HttpsError("failed-precondition", "This auction has already ended.");
                    if (productData.sellerId === bidderId) throw new HttpsError("failed-precondition", "You cannot bid on your own item.");
                    const currentBid = auction.currentBid || auction.startingPrice;
                    if (amount <= currentBid) throw new HttpsError("failed-precondition", `Your bid must be higher than the current bid of $${currentBid.toFixed(2)}.`);
                    const userDoc = await db.collection("users").doc(bidderId).get();
                    const bidderName = userDoc.data()?.displayName || "Anonymous Bidder";
                    const previousLeadingBidderId = auction.leadingBidderId;
                    const newAuctionInfo = { ...auction, currentBid: amount, leadingBidderId: bidderId };
                    transaction.update(productRef, { auctionInfo: newAuctionInfo });
                    const bidRef = productRef.collection("bids").doc();
                    transaction.set(bidRef, { bidderId, bidderName, amount, timestamp: FieldValue.serverTimestamp() });
                    if (previousLeadingBidderId && previousLeadingBidderId !== bidderId) {
                        const outbidNotification = { title: "You've been outbid! 💔", body: `Someone placed a higher bid on "${productData.name}".`, type: "outbid", data: { type: "outbid", productId } };
                        await storeNotificationRecord(previousLeadingBidderId, outbidNotification);
                        await sendPushNotifications(previousLeadingBidderId, outbidNotification);
                    }
                    return { success: true, message: "Bid placed successfully." };
                });
            }
            
            case "getRankedProducts": {
                const { latitude: buyerLat, longitude: buyerLon } = data;
                const hasBuyerLocation = (typeof buyerLat === "number" && typeof buyerLon === "number");
                const ratingWeight = 0.6, distanceWeight = 0.4, maxDistanceKm = 100, initialLimit = 200, finalLimit = 50;
                const now = admin.firestore.Timestamp.now();
                try {
                    const productsSnapshot = await db.collection("products").where("isPaid", "==", true).where("isSold", "==", false).where("expiresAt", ">", now).orderBy("expiresAt", "asc").limit(initialLimit).get();
                    const candidateProducts: any[] = productsSnapshot.docs.map((doc) => ({ id: doc.id, ...doc.data(), score: 0, distanceKm: null }));
                    if (hasBuyerLocation) {
                        candidateProducts.forEach((prod) => {
                            const sellerLoc = prod.sellerLocation;
                            if (sellerLoc) {
                                const distanceKm = getDistanceFromLatLonInKm(buyerLat!, buyerLon!, sellerLoc.latitude, sellerLoc.longitude);
                                prod.distanceKm = distanceKm;
                                const normalizedRating = (prod.sellerAverageRating || 0) / 5.0;
                                const normalizedDistance = (distanceKm !== null && distanceKm <= maxDistanceKm) ? 1.0 - (distanceKm / maxDistanceKm) : 0;
                                prod.score = (ratingWeight * normalizedRating) + (distanceWeight * normalizedDistance);
                            }
                        });
                    } else {
                        candidateProducts.forEach((p) => p.score = ratingWeight * ((p.sellerAverageRating || 0) / 5.0));
                    }
                    candidateProducts.sort((a, b) => (b.score || 0) - (a.score || 0));
                    const rankedProducts = candidateProducts.slice(0, finalLimit).map((p) => ({ id: p.id, distanceKm: p.distanceKm }));
                    return { rankedProducts };
                } catch (error) {
                    logger.error("Error getting ranked products:", error);
                    throw new HttpsError("internal", "Failed to retrieve product ranking.");
                }
            }

            case "createPaymentIntent": {
                try {
                    const stripe = new Stripe(STRIPE_SECRET_KEY.value());
                    const paymentIntent = await stripe.paymentIntents.create({ amount: 700, currency: "usd", automatic_payment_methods: { enabled: true } });
                    return { clientSecret: paymentIntent.client_secret };
                } catch (error: any) {
                    logger.error("Stripe error in createPaymentIntent:", error);
                    throw new HttpsError("internal", error.message || "An internal error occurred.");
                }
            }

            case "markProductAsPaid": {
                checkAuth();
                const { productId, isAuction, auctionDurationDays } = data;
                if (!productId) throw new HttpsError("invalid-argument", "Product ID is required.");
                try {
                    const productRef = db.collection("products").doc(productId);
                    let duration = LISTING_DURATION_DAYS;
                    if (isAuction && typeof auctionDurationDays === "number" && auctionDurationDays > 0) duration = auctionDurationDays;
                    const expiryDate = new Date();
                    expiryDate.setDate(expiryDate.getDate() + duration);
                    const updates: { [key: string]: any } = { isPaid: true, paidAt: FieldValue.serverTimestamp(), expiresAt: Timestamp.fromDate(expiryDate) };
                    if (isAuction) updates["auctionInfo.endTime"] = Timestamp.fromDate(expiryDate);
                    await productRef.update(updates);
                    const productData = (await productRef.get()).data();
                    if (productData?.sellerId) {
                        const payload = { title: "Your Listing is Live! ✨", body: `Your item "${productData.name}" is now listed for ${duration} days.`, type: "listing_live", data: { type: "listing_live", productId } };
                        await storeNotificationRecord(productData.sellerId, payload);
                        await sendPushNotifications(productData.sellerId, payload);
                    }
                    return { success: true, message: "Product listing activated." };
                } catch (error) {
                    logger.error(`Error in markProductAsPaid for product ${productId}:`, error);
                    throw new HttpsError("internal", "Could not update product status.");
                }
            }

            case "markListingAsSold": {
                checkAuth();
                const { productId } = data;
                if (!productId) throw new HttpsError("invalid-argument", "A valid productId must be provided.");
                const productRef = db.collection("products").doc(productId);
                try {
                    const productDoc = await productRef.get();
                    if (!productDoc.exists) throw new HttpsError("not-found", "Product does not exist.");
                    if (productDoc.data()?.sellerId !== request.auth!.uid) throw new HttpsError("permission-denied", "You are not authorized to modify this listing.");
                    await productRef.update({ isSold: true, soldAt: FieldValue.serverTimestamp() });
                    return { success: true, message: "Listing marked as sold." };
                } catch (error) {
                    logger.error(`Error in markListingAsSold for product ${productId}:`, error);
                    throw new HttpsError("internal", "An error occurred.");
                }
            }

            case "relistProduct": {
                checkAuth();
                const { productId } = data;
                if (!productId) throw new HttpsError("invalid-argument", "A valid productId must be provided.");
                const productRef = db.collection("products").doc(productId);
                try {
                    const productDoc = await productRef.get();
                    const productData = productDoc.data();
                    if (!productDoc.exists || !productData) throw new HttpsError("not-found", "Product does not exist.");
                    if (productData.sellerId !== request.auth!.uid) throw new HttpsError("permission-denied", "You are not authorized to modify this listing.");
                    if (new Date() >= productData.expiresAt.toDate()) throw new HttpsError("failed-precondition", "This listing has fully expired and cannot be relisted. Please create a new listing.");
                    await productRef.update({ isSold: false, soldAt: null });
                    const payload = { title: "Your Item is Relisted!", body: `Your item "${productData.name}" is now active again.`, type: "listing_relisted", data: { type: "listing_relisted", productId } };
                    await storeNotificationRecord(productData.sellerId, payload);
                    await sendPushNotifications(productData.sellerId, payload);
                    return { success: true, message: "Listing has been relisted." };
                } catch (error) {
                    logger.error(`Error in relistProduct for product ${productId}:`, error);
                    if (error instanceof HttpsError) throw error;
                    throw new HttpsError("internal", "An error occurred while relisting.");
                }
            }

            case "getListingDetailsFromTitle": {
                checkAuth();
                const { title } = data;
                if (!title) throw new HttpsError("invalid-argument", "A valid product title is required.");
                // ✅ FIXED: Use the new secret management system.
                const apiKey = GEMINI_API_KEY.value();
                if (!apiKey) throw new HttpsError("internal", "AI service is not configured correctly.");
                try {
                    const genAI = new GoogleGenerativeAI(apiKey);
                    const model = genAI.getGenerativeModel({ model: "gemini-2.5-flash" });
                    const prompt = `Based on the product title "${title}", generate a compelling product description (2-3 sentences) and suggest the most appropriate category. Respond with a valid JSON object only: {"description": "...", "category": "..."}. Valid Categories: ${PRODUCT_CATEGORIES_FOR_AI.join(", ")}`;
                    const result = await model.generateContent(prompt);
                    const responseText = result.response.text();
                    let suggestions = JSON.parse(responseText.match(/{[\s\S]*}/)?.[0] || "{}");
                    if (!PRODUCT_CATEGORIES_FOR_AI.includes(suggestions.category)) suggestions.category = "Other";
                    return { suggestions };
                } catch (error) {
                    logger.error("Error in getListingDetailsFromTitle:", error);
                    throw new HttpsError("internal", "Could not generate suggestions.");
                }
            }

            case "askGemini": {
                const { prompt } = data;
                if (!prompt) throw new HttpsError("invalid-argument", "A non-empty 'prompt' is required.");
                // ✅ FIXED: Use the new secret management system.
                const apiKey = GEMINI_API_KEY.value();
                if (!apiKey) throw new HttpsError("internal", "API key not configured.");
                try {
                    const genAI = new GoogleGenerativeAI(apiKey);
                    const model = genAI.getGenerativeModel({ model: "gemini-2.5-flash" });
                    const result = await model.generateContent(prompt);
                    return { reply: result.response.text() };
                } catch (error) {
                    logger.error("Error calling Gemini in askGemini:", error);
                    throw new HttpsError("internal", "Failed to process request with AI model.");
                }
            }
            
            case "clearAllNotifications": {
                checkAuth();
                const userId = request.auth!.uid;
                const notificationsRef = db.collection("users").doc(userId).collection("notifications");
                try {
                    const snapshot = await notificationsRef.get();
                    if (snapshot.empty) return { success: true, message: "No notifications to clear." };
                    const batch = db.batch();
                    snapshot.docs.forEach((doc) => batch.delete(doc.ref));
                    await batch.commit();
                    logger.log(`Cleared ${snapshot.size} notifications for user ${userId}.`);
                    return { success: true, message: "All notifications cleared." };
                } catch (error) {
                    logger.error(`Error clearing notifications for user ${userId}:`, error);
                    throw new HttpsError("internal", "Could not clear notifications.");
                }
            }

            case "requestVerification": {
                checkAuth();
                const userId = request.auth!.uid;
                try {
                    await db.collection("users").doc(userId).update({ verificationRequested: true });
                    logger.log(`User ${userId} has requested verification.`);
                    return { success: true, message: "Verification request submitted." };
                } catch (error) {
                    logger.error(`Error requesting verification for user ${userId}:`, error);
                    throw new HttpsError("internal", "Could not submit verification request.");
                }
            }
            
            case "approveVerificationRequest": {
                if (!request.auth || !request.auth.token.admin) throw new HttpsError("permission-denied", "This function can only be called by an administrator.");
                const { userId } = data;
                if (!userId) throw new HttpsError("invalid-argument", "A valid userId must be provided.");
                try {
                    const userRef = db.collection("users").doc(userId);
                    await userRef.update({ isVerified: true, verificationRequested: false });
                    const notificationPayload = { title: "You're Verified! ✅", body: "Congratulations! Your YahdSell account has been verified.", type: "account_verified", data: { type: "account_verified", url: `yahdsell2://profile/${userId}` } };
                    await storeNotificationRecord(userId, notificationPayload);
                    await sendPushNotifications(userId, notificationPayload);
                    logger.log(`Admin ${request.auth.uid} has verified user ${userId}.`);
                    return { success: true, message: `User ${userId} has been verified.` };
                } catch (error) {
                    logger.error(`Error verifying user ${userId}:`, error);
                    throw new HttpsError("internal", "An error occurred while verifying the user.");
                }
            }

            case "saveSearch": {
                checkAuth();
                const { query, category, minPrice, maxPrice, condition } = data;
                const userId = request.auth!.uid;
                if (!query && !category) throw new HttpsError("invalid-argument", "Either a search query or a category is required.");
                try {
                    const searchRef = db.collection("users").doc(userId).collection("savedSearches").doc();
                    await searchRef.set({ userId, query: query || null, category: category || null, minPrice: Number(minPrice) || null, maxPrice: Number(maxPrice) || null, condition: condition || null, createdAt: FieldValue.serverTimestamp() });
                    return { success: true, message: "Search saved successfully!" };
                } catch (error) {
                    logger.error(`Error saving search for user ${userId}:`, error);
                    throw new HttpsError("internal", "Could not save your search.");
                }
            }
            
            default:
                throw new HttpsError("not-found", "The requested API action is not valid.");
        }
    }
);

// --- Trigger-based Functions ---

export const notifyOnSavedSearchMatch = onDocumentCreated("products/{productId}", async (event) => {
    const product = event.data?.data();
    const productId = event.params.productId;

    if (!product || !product.isPaid || product.isSold) return;
    logger.log(`Checking saved searches for new product: ${productId}`);

    try {
        let query: admin.firestore.Query = db.collectionGroup("savedSearches");
        if (product.category && product.category !== "All") {
            query = query.where("category", "in", [product.category, "All", null]);
        }
        const searchesSnapshot = await query.get();
        if (searchesSnapshot.empty) {
            logger.log("No potentially matching saved searches found.");
            return;
        }
        const notifications: Promise<void>[] = [];
        const notifiedUsers = new Set<string>();
        searchesSnapshot.forEach((doc) => {
            const search = doc.data();
            if (notifiedUsers.has(search.userId)) return;
            const matchesQuery = !search.query || product.name.toLowerCase().includes(search.query.toLowerCase());
            const matchesCategory = !search.category || search.category === "All" || search.category === product.category;
            const matchesMinPrice = !search.minPrice || product.price >= search.minPrice;
            const matchesMaxPrice = !search.maxPrice || product.price <= search.maxPrice;
            const matchesCondition = !search.condition || search.condition === "Any Condition" || search.condition === product.condition;
            if (matchesQuery && matchesCategory && matchesMinPrice && matchesMaxPrice && matchesCondition) {
                const payload = { title: "An item you're looking for is here! 👀", body: `A new listing for "${product.name}" matches your saved search.`, type: "saved_search_match", data: { type: "saved_search_match", productId: productId } };
                notifications.push(sendPushNotifications(search.userId, payload));
                notifiedUsers.add(search.userId);
            }
        });
        if (notifications.length > 0) {
            await Promise.all(notifications);
            logger.log(`Sent ${notifications.length} saved search notifications for product ${productId}.`);
        }
    } catch (error) {
        logger.error(`Error in notifyOnSavedSearchMatch for product ${productId}:`, error);
    }
});


export const updateUserProductsOnProfileChange = onDocumentUpdated("users/{userId}", async (event) => {
    const beforeData = event.data?.before.data();
    const afterData = event.data?.after.data();
    if (!beforeData || !afterData) return;
    const nameChanged = beforeData.displayName !== afterData.displayName;
    const picChanged = beforeData.profilePicUrl !== afterData.profilePicUrl;
    const verificationChanged = beforeData.isVerified !== afterData.isVerified;
    const ratingChanged = beforeData.averageRating !== afterData.averageRating;
    if (!nameChanged && !picChanged && !verificationChanged && !ratingChanged) return;
    const userId = event.params.userId;
    const productUpdates: { [key: string]: any } = {};
    if (nameChanged) productUpdates.sellerDisplayName = afterData.displayName;
    if (picChanged) productUpdates.sellerProfilePicUrl = afterData.profilePicUrl || null;
    if (verificationChanged) productUpdates.sellerIsVerified = afterData.isVerified;
    if (ratingChanged) productUpdates.sellerAverageRating = afterData.averageRating;
    const productsQuery = db.collection("products").where("sellerId", "==", userId);
    try {
      const productSnapshot = await productsQuery.get();
      if (productSnapshot.empty) return;
      const batch = db.batch();
      productSnapshot.docs.forEach((doc) => batch.update(doc.ref, productUpdates));
      await batch.commit();
      logger.log(`Successfully updated ${productSnapshot.size} products for seller ${userId}.`);
    } catch (error) {
      logger.error(`Error updating products for seller ${userId}:`, error);
    }
});

export const updateSellerRating = onDocumentWritten("reviews/{reviewId}", async (event) => {
    const data = event.data?.after.data() || event.data?.before.data();
    if (!data?.sellerId) return;
    const { sellerId } = data;
    const sellerRef = db.collection("users").doc(sellerId);
    const reviewsQuery = db.collection("reviews").where("sellerId", "==", sellerId);
    try {
      const reviewsSnapshot = await reviewsQuery.get();
      const ratingCount = reviewsSnapshot.size;
      const totalRatingSum = reviewsSnapshot.docs.reduce((sum, doc) => sum + (doc.data().rating || 0), 0);
      const averageRating = ratingCount > 0 ? Math.round((totalRatingSum / ratingCount) * 10) / 10 : 0;
      await sellerRef.update({ ratingCount, averageRating });
      logger.log(`Updated rating for seller ${sellerId} to ${averageRating}.`);
    } catch (error) {
      logger.error(`Error updating rating for seller ${sellerId}:`, error);
    }
});

export const sendNewOfferNotificationToSeller = onDocumentCreated("products/{productId}/offers/{offerId}", async (event) => {
    const offerData = event.data?.data();
    if (!offerData) return;
    const { sellerId, buyerId, buyerName, offerAmount = 0 } = offerData;
    const productName = (await db.collection("products").doc(event.params.productId).get()).data()?.name || "your item";
    const payload = { title: `New Offer on "${productName}"`, body: `${buyerName} offered $${offerAmount.toFixed(2)}.`, type: "new_offer", data: { type: "new_offer", productId: event.params.productId } };
    await storeNotificationRecord(sellerId, payload);
    await sendPushNotifications(sellerId, payload);
    await sendSystemChatMessage(sellerId, buyerId, `A new offer of $${offerAmount.toFixed(2)} was made by ${buyerName} for "${productName}".`);
});

export const sendOfferStatusUpdateNotificationToBuyer = onDocumentUpdated("products/{productId}/offers/{offerId}", async (event) => {
    const beforeData = event.data?.before.data();
    const afterData = event.data?.after.data();
    if (!beforeData || !afterData || beforeData.status === afterData.status) return;

    const { status, buyerId, sellerId, offerAmount = 0 } = afterData;
    const offerId = event.params.offerId;

    if (!buyerId || !sellerId || !["accepted", "rejected"].includes(status)) return;
    const productId = event.params.productId;
    const productRef = db.collection("products").doc(productId);
    const productName = (await productRef.get()).data()?.name || "your offered item";
    if (status === "accepted") {
        await productRef.update({ isSold: true, soldAt: FieldValue.serverTimestamp() });
        logger.log(`Product ${productId} marked as sold due to offer acceptance.`);
        const offersRef = db.collection("products").doc(productId).collection("offers");
        const pendingOffersSnapshot = await offersRef.where("status", "==", "pending").get();
        const batch = db.batch();
        pendingOffersSnapshot.docs.forEach((doc) => {
            if (doc.id !== offerId) batch.update(doc.ref, { status: "rejected" });
        });
        await batch.commit();
        logger.log(`Rejected ${pendingOffersSnapshot.size - 1} other offers for product ${productId}.`);
    }
    const payload = { title: status === "accepted" ? `Offer Accepted! 🎉` : `Offer Update for "${productName}"`, body: status === "accepted" ? `Your offer of $${offerAmount.toFixed(2)} has been accepted.` : `Your offer was declined.`, type: `offer_${status}`, data: { type: `offer_${status}`, productId: event.params.productId } };
    await storeNotificationRecord(buyerId, payload);
    await sendPushNotifications(buyerId, payload);
    const chatMessage = status === "accepted" ? `🎉 Your offer for "${productName}" was ACCEPTED!` : `Your offer for "${productName}" was declined.`;
    await sendSystemChatMessage(sellerId, buyerId, chatMessage);
});


// --- Scheduled Functions ---
export const processEndedAuctions = onSchedule("every 15 minutes", async () => {
    const now = Timestamp.now();
    const query = db.collection("products")
        .where("auctionInfo.endTime", "<=", now)
        .where("isSold", "==", false)
        .where("isPaid", "==", true);

    const snapshot = await query.get();
    if (snapshot.empty) {
        logger.log("No ended auctions to process.");
        return;
    }

    const promises = snapshot.docs.map(async (doc) => {
        const product = doc.data();
        const auction = product.auctionInfo;

        if (auction && auction.leadingBidderId) {
            await doc.ref.update({ isSold: true, soldAt: FieldValue.serverTimestamp() });
            const winnerPayload = { title: "You won the auction! 🏆", body: `Congratulations! You won the auction for "${product.name}".`, type: "auction_win", data: { type: "auction_win", productId: doc.id } };
            await storeNotificationRecord(auction.leadingBidderId, winnerPayload);
            await sendPushNotifications(auction.leadingBidderId, winnerPayload);
            const sellerPayload = { title: "Your auction has ended!", body: `Your auction for "${product.name}" has ended. The winning bid was $${auction.currentBid.toFixed(2)}.`, type: "auction_end", data: { type: "auction_end", productId: doc.id } };
            await storeNotificationRecord(product.sellerId, sellerPayload);
            await sendPushNotifications(product.sellerId, sellerPayload);
            await sendSystemChatMessage(product.sellerId, auction.leadingBidderId, `The auction for "${product.name}" has ended. Please arrange payment and collection.`);
            logger.log(`Processed auction win for product ${doc.id} by user ${auction.leadingBidderId}.`);
        } else {
            const sellerPayload = { title: "Your auction has ended", body: `Unfortunately, your auction for "${product.name}" ended without any bids.`, type: "auction_end_no_bids", data: { type: "auction_end_no_bids", productId: doc.id } };
            await storeNotificationRecord(product.sellerId, sellerPayload);
            await sendPushNotifications(product.sellerId, sellerPayload);
            logger.log(`Processed ended auction for product ${doc.id} with no bids.`);
        }
    });

    await Promise.all(promises);
});

export const cleanupSoldOrExpiredListings = onSchedule("every 24 hours", async () => {
    const now = new Date();
    const cutoffDate = new Date(now.getTime() - (LISTING_DURATION_DAYS + GRACE_PERIOD_DAYS) * 24 * 60 * 60 * 1000);
    const timestampCutoff = Timestamp.fromDate(cutoffDate);
    const oldListingsQuery = db.collection("products").where("paidAt", "<", timestampCutoff);
    try {
        const snapshot = await oldListingsQuery.get();
        if (snapshot.empty) {
            logger.log("No old listings to clean up.");
            return;
        }
        const batch = db.batch();
        const deletePromises: Promise<any>[] = [];
        snapshot.docs.forEach((doc) => {
            const { imageStoragePaths = [], videoStoragePath } = doc.data();
            imageStoragePaths.forEach((path: string) => deletePromises.push(bucket.file(path).delete().catch((e) => logger.error(`Failed to delete image ${path}`, e))));
            if (videoStoragePath) deletePromises.push(bucket.file(videoStoragePath).delete().catch((e) => logger.error(`Failed to delete video ${videoStoragePath}`, e)));
            batch.delete(doc.ref);
        });
        await Promise.all(deletePromises);
        await batch.commit();
        logger.log(`Successfully cleaned up and deleted ${snapshot.size} old listings.`);
    } catch (error) {
        logger.error("Error during cleanup of old listings:", error);
    }
});
