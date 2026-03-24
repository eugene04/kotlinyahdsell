import { algoliasearch } from 'algoliasearch';
import type { SearchClient } from 'algoliasearch';
import { logger } from "firebase-functions";
import { HttpsError } from "firebase-functions/v2/https";
import { ALGOLIA_APP_ID, ALGOLIA_ADMIN_KEY } from "../config";

let algoliaClient: SearchClient | null = null;

export function getAlgoliaClient(): SearchClient {
    if (!algoliaClient) {
        const appId = ALGOLIA_APP_ID.value();
        const adminKey = ALGOLIA_ADMIN_KEY.value();
        if (!appId || !adminKey) {
            logger.error("FATAL: Algolia App ID or Admin Key secret is not configured or accessible.");
            throw new HttpsError("internal", "Algolia configuration secrets are missing.");
        }
        algoliaClient = algoliasearch(appId, adminKey);
    }
    return algoliaClient!; // Non-null assertion
}
