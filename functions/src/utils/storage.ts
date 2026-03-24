import { logger } from "firebase-functions";

/**
 * Helper to attempt extracting a GCS path from a download URL.
 */
export function urlToStoragePath(url: string): string | null {
    try {
        const decodedUrl = decodeURIComponent(url);
        // Match the path part between /o/ and ?alt=media
        const pathRegex = /\/o\/(.*?)\?alt=media/;
        const match = decodedUrl.match(pathRegex);
        if (match && match[1]) {
            // Replace Firebase URL encoding like %2F with /
            return match[1].replace(/%2F/g, '/');
        }
        logger.warn(`Could not extract GCS path from URL format: ${url}`);
        return null;
    } catch (e) {
        logger.error(`Error decoding or parsing storage URL: ${url}`, e);
        return null;
    }
}
