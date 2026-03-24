import * as admin from "firebase-admin";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";

admin.initializeApp();
export const db = getFirestore();
export const bucket = getStorage().bucket();
