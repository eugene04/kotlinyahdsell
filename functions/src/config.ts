import { defineSecret } from "firebase-functions/params";

// Secrets
export const STRIPE_SECRET_KEY = defineSecret("STRIPE_SECRET_KEY");
export const GOOGLE_MAPS_API_KEY = defineSecret("GOOGLE_MAPS_API_KEY");
export const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
export const ALGOLIA_APP_ID = defineSecret("ALGOLIA_APP_ID");
export const ALGOLIA_ADMIN_KEY = defineSecret("ALGOLIA_ADMIN_KEY");

// Constants
export const ALGOLIA_INDEX_NAME = "products";
export const LISTING_DURATION_DAYS = 7;
export const GRACE_PERIOD_DAYS = 7;
export const PRODUCT_CATEGORIES_FOR_AI = [
    "Electronics", "Clothing & Apparel", "Home & Garden", "Furniture", "Vehicles",
    "Books, Movies & Music", "Collectibles & Art", "Sports & Outdoors",
    "Toys & Hobbies", "Baby & Kids", "Health & Beauty", "Other"
];
export const PRODUCT_CONDITIONS_FOR_AI = ["New", "Used - Like New", "Used - Good", "Used - Fair"];

export const DEFAULT_FEES = {
    listingFeeCents: 700,
    bumpFeeCents: 100,
    featureFeeCents: 500,
};
