import { logger } from "firebase-functions";
import { HttpsError } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import { db } from "../firebase";
import { ALGOLIA_INDEX_NAME } from "../config";
import { getAlgoliaClient } from "../utils/algolia";
import { getDistanceFromLatLonInKm } from "../utils/geo";

export async function getRankedProducts(data: any, userId: string | undefined) {
    const client = getAlgoliaClient();
    if (!client) {
        throw new HttpsError("internal", "Search service not initialized.");
    }

    const {
        latitude: buyerLat,
        longitude: buyerLon,
        query = "",
        filters = {}
    } = data as {
        latitude?: number,
        longitude?: number,
        query?: string,
        filters?: any
    };

    const hasBuyerLocation = (typeof buyerLat === "number" && typeof buyerLon === "number");
    const nowTimestamp = Math.floor(Date.now() / 1000);

    const userType = userId ? "authenticated" : "guest";
    logger.log(`getRankedProducts called by ${userType} user${userId ? ` (${userId})` : ""}`);

    const baseFilters = `isPaid = 1 AND isSold = 0 AND expiresAt_timestamp > ${nowTimestamp}`;
    const facetFilterArray: string[][] = [];
    const numericFilterArray: string[] = [];

    if (filters.category && filters.category !== "All") {
        facetFilterArray.push([`category:${filters.category}`]);
    }
    if (filters.condition && filters.condition !== "Any Condition") {
        facetFilterArray.push([`condition:${filters.condition}`]);
    }
    if (typeof filters.minPrice === 'number') {
        numericFilterArray.push(`price >= ${filters.minPrice}`);
    }
    if (typeof filters.maxPrice === 'number') {
        numericFilterArray.push(`price <= ${filters.maxPrice}`);
    }

    logger.log(
        `Algolia search query: "${query}", Base Filters: "${baseFilters}", ` +
        `Facet Filters: ${JSON.stringify(facetFilterArray)}, ` +
        `Numeric Filters: ${JSON.stringify(numericFilterArray)}, ` +
        `Location: ${hasBuyerLocation}, User: ${userType}`
    );

    try {
        const searchParams: any = {
            query: query,
            hitsPerPage: 50,
            filters: baseFilters,
            facets: ['category', 'condition']
        };

        if (facetFilterArray.length > 0) {
            searchParams.facetFilters = facetFilterArray;
        }
        if (numericFilterArray.length > 0) {
            searchParams.numericFilters = numericFilterArray;
        }

        if (hasBuyerLocation) {
            searchParams.aroundLatLng = `${buyerLat},${buyerLon}`;
            searchParams.minimumAroundRadius = 1000;
        }

        const response: any = await client.searchSingleIndex({
            indexName: ALGOLIA_INDEX_NAME,
            searchParams: searchParams,
        });

        logger.log("Algolia search response FACETS:", JSON.stringify(response.facets || {}));

        const rankedProducts = response.hits.map((hit: any) => {
            let distanceKm: number | null = null;
            if (hasBuyerLocation && hit._geoloc) {
                distanceKm = getDistanceFromLatLonInKm(
                    buyerLat!,
                    buyerLon!,
                    hit._geoloc.lat,
                    hit._geoloc.lng
                );
            }
            return {
                ...hit,
                id: hit.objectID,
                distanceKm: distanceKm
            };
        });

        logger.log(`Returning ${rankedProducts.length} ranked product IDs from Algolia for ${userType} user.`);

        return {
            rankedProducts,
            facets: response.facets || {}
        };

    } catch (error: any) {
        logger.error(`Error searching Algolia V5 for ranked products (${userType} user):`, error.message || error);
        throw new HttpsError("internal", "Failed to retrieve product ranking.");
    }
}

export async function getNearbyProducts(data: any) {
    const client = getAlgoliaClient();
    if (!client) { throw new HttpsError("internal", "Search service not initialized."); }
    const { latitude, longitude, radiusKm = 10, filters = {} } = data as { latitude?: number, longitude?: number, radiusKm?: number, filters?: any };
    if (typeof latitude !== 'number' || typeof longitude !== 'number') throw new HttpsError("invalid-argument", "Valid latitude and longitude are required.");
    if (typeof radiusKm !== 'number' || radiusKm <= 0) throw new HttpsError("invalid-argument", "Radius must be a positive number in kilometers.");
    const radiusMeters = Math.round(radiusKm * 1000);
    const nowTimestamp = Math.floor(Date.now() / 1000);

    const baseFilters = `isPaid = 1 AND isSold = 0 AND expiresAt_timestamp > ${nowTimestamp}`;
    const facetFilterArray: string[][] = [];
    const numericFilterArray: string[] = [];

    if (filters.category && filters.category !== "All") {
        facetFilterArray.push([`category:${filters.category}`]);
    }
    if (filters.condition && filters.condition !== "Any Condition") {
        facetFilterArray.push([`condition:${filters.condition}`]);
    }
    if (typeof filters.minPrice === 'number') {
        numericFilterArray.push(`price >= ${filters.minPrice}`);
    }
    if (typeof filters.maxPrice === 'number') {
        numericFilterArray.push(`price <= ${filters.maxPrice}`);
    }

    logger.log(`Fetching nearby products from Algolia V5 for lat=${latitude}, lon=${longitude}, radius=${radiusKm}km. Base Filters: ${baseFilters}, Facet Filters: ${JSON.stringify(facetFilterArray)}, Numeric Filters: ${JSON.stringify(numericFilterArray)}`);

    try {
        const searchParams: any = {
            query: "",
            aroundLatLng: `${latitude},${longitude}`,
            aroundRadius: radiusMeters,
            minimumAroundRadius: 1000,
            hitsPerPage: 100,
            filters: baseFilters,
            facets: ['category', 'condition']
        };

        if (facetFilterArray.length > 0) {
            searchParams.facetFilters = facetFilterArray;
        }
        if (numericFilterArray.length > 0) {
            searchParams.numericFilters = numericFilterArray;
        }

        const response: any = await client.searchSingleIndex({
            indexName: ALGOLIA_INDEX_NAME,
            searchParams: searchParams,
        });

        logger.log("Algolia search response FACETS (Nearby):", JSON.stringify(response.facets || {}));

        const parseTimestampToString = (ts: any): string | null => typeof ts === 'number' ? new Date(ts * 1000).toISOString() : null;

        const nearbyProducts = response.hits.map((hit: any) => {
            let distanceKm: number | null = null;
            if (hit._geoloc) {
                distanceKm = getDistanceFromLatLonInKm(latitude, longitude, hit._geoloc.lat, hit._geoloc.lng);
            }
            return {
                id: hit.objectID, name: hit.name || "", description: hit.description || "",
                price: hit.price || 0.0, category: hit.category || "", condition: hit.condition || "",
                imageUrls: hit.imageUrls || [], videoUrl: hit.videoUrl || null,
                sellerId: hit.sellerId || "", sellerDisplayName: hit.sellerDisplayName || "",
                sellerProfilePicUrl: hit.sellerProfilePicUrl || null, sellerIsVerified: hit.sellerIsVerified || false,
                sellerAverageRating: hit.sellerAverageRating || 0.0,
                sellerLocationLat: hit._geoloc?.lat || null,
                sellerLocationLon: hit._geoloc?.lng || null,
                itemAddress: hit.itemAddress || null, distanceKm: distanceKm,
                isSold: hit.isSold || false, isPaid: hit.isPaid || false, auctionInfo: hit.auctionInfo || null,
                createdAt: parseTimestampToString(hit.createdAt_timestamp),
                paidAt: parseTimestampToString(hit.paidAt_timestamp),
                soldAt: parseTimestampToString(hit.soldAt_timestamp),
                expiresAt: parseTimestampToString(hit.expiresAt_timestamp),
                viewCount: hit.viewCount || 0, isFeatured: hit.isFeatured || false,
                lastBumpedAt: parseTimestampToString(hit.lastBumpedAt_timestamp),
                auctionDurationDays: hit.auctionDurationDays || null
            };
        }).filter((prod: { distanceKm: number | null }) => prod.distanceKm != null && prod.distanceKm <= radiusKm);
        logger.log(`Returning ${nearbyProducts.length} nearby products from Algolia.`);
        return { nearbyProducts: nearbyProducts, facets: response.facets || {} };
    } catch (error: any) {
        logger.error("Error getting nearby products from Algolia V5:", error.message || error);
        throw new HttpsError("internal", "Failed to retrieve nearby products.");
    }
}

export async function saveSearch(data: any, userId: string | undefined) {
    const { query, category, minPrice, maxPrice, condition } = data;
    if (!userId) throw new HttpsError("unauthenticated", "Authentication required.");
    if (!query && !category && minPrice == null && maxPrice == null && !condition) throw new HttpsError("invalid-argument", "At least one search criteria is required.");
    try {
        const searchRef = db.collection("users").doc(userId).collection("savedSearches").doc();
        await searchRef.set({
            userId, query: query || null, category: category || null,
            minPrice: typeof minPrice === 'number' ? minPrice : null,
            maxPrice: typeof maxPrice === 'number' ? maxPrice : null,
            condition: condition || null, createdAt: FieldValue.serverTimestamp()
        });
        return { success: true, message: "Search saved successfully!" };
    } catch (error) {
        logger.error(`Error saving search for user ${userId}:`, error);
        throw new HttpsError("internal", "Could not save your search.");
    }
}
