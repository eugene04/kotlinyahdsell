import { setGlobalOptions } from "firebase-functions/v2";

// Set global options for V2 functions (optional, but good practice)
setGlobalOptions({ maxInstances: 10 });

// Export Shared Infrastructure (if needed externally, though usually internal)
export * from "./firebase";
export * from "./config";

// Export Triggers
export * from "./triggers/chats";
export * from "./triggers/userTriggers";
export * from "./triggers/reviews";
export * from "./triggers/offers";
export * from "./triggers/scheduled";

// Export Public API
export * from "./api/routes";
