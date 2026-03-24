import {GoogleGenerativeAI, SchemaType} from "@google/generative-ai";
import axios from "axios";
import {logger} from "firebase-functions";
import {HttpsError} from "firebase-functions/v2/https";
import {GEMINI_API_KEY, PRODUCT_CATEGORIES_FOR_AI, PRODUCT_CONDITIONS_FOR_AI} from "../config";

export async function getListingDetailsFromTitle(data: any) {
  const {title} = data;
  if (!title) throw new HttpsError("invalid-argument", "A valid product title is required.");
  const apiKey = GEMINI_API_KEY.value();
  if (!apiKey) throw new HttpsError("internal", "AI service is not configured correctly.");
  try {
    const genAI = new GoogleGenerativeAI(apiKey);
    const model = genAI.getGenerativeModel({model: "gemini-3.1-pro-preview"});
    const prompt = `Based on the product title "${title}", generate a compelling product description (2-3 sentences) and suggest the most appropriate category. Respond ONLY with a valid JSON object like this: {"description": "...", "category": "..."}. Do not include markdown formatting like \`\`\`json. Valid Categories are strictly limited to: ${PRODUCT_CATEGORIES_FOR_AI.join(", ")}`;
    const result = await model.generateContent(prompt);
    const responseText = result.response.text();
    let suggestions;
    try {
      const jsonMatch = responseText.match(/{[\s\S]*}/);
      if (jsonMatch) suggestions = JSON.parse(jsonMatch[0]);
      else throw new Error("No JSON object found in the response.");
    } catch (parseError) {
      logger.error("Failed to parse Gemini response as JSON:", responseText, parseError);
      throw new HttpsError("internal", "Could not process AI suggestions (format error).");
    }
    if (!suggestions.category || !PRODUCT_CATEGORIES_FOR_AI.includes(suggestions.category)) {
      logger.warn(`Gemini suggested invalid or missing category '${suggestions.category}', defaulting to 'Other'. Response: ${responseText}`);
      suggestions.category = "Other";
    }
    if (!suggestions.description) {
      logger.warn(`Gemini did not provide a description. Response: ${responseText}`);
      suggestions.description = "";
    }
    return {suggestions};
  } catch (error: any) {
    logger.error("Error in getListingDetailsFromTitle:", error);
    if (error instanceof HttpsError) throw error;
    if (error.message && (error.message.includes("quota") || error.message.includes("RESOURCE_EXHAUSTED") || error.message.includes("API key not valid"))) {
      throw new HttpsError("resource-exhausted", `AI service error: ${error.message}. Please try again later.`);
    }
    throw new HttpsError("internal", "Could not generate suggestions due to an internal error.");
  }
}

export async function visualSearch(data: any) {
  const {imageUrl} = data;
  if (!imageUrl) throw new HttpsError("invalid-argument", "A valid 'imageUrl' is required.");
  const apiKey = GEMINI_API_KEY.value();
  if (!apiKey) throw new HttpsError("internal", "AI service is not configured correctly.");
  try {
    const imageResponse = await axios.get(imageUrl, {responseType: "arraybuffer"});
    const base64Image = Buffer.from(imageResponse.data, "binary").toString("base64");
    const mimeType = imageResponse.headers["content-type"] || "image/jpeg";
    if (base64Image.length * 0.75 > 4 * 1024 * 1024) throw new HttpsError("invalid-argument", "Image size is too large (max 4MB).");
    const imagePart = {inlineData: {data: base64Image, mimeType: mimeType}};
    const prompt = `Analyze this image and identify the main product shown. Respond with a concise search query (2-4 words max) suitable for finding similar items, and suggest the most appropriate category for listing it. Respond ONLY with a valid JSON object like this: {"searchQuery": "...", "category": "..."}. Do not include markdown formatting like \`\`\`json. Valid Categories are strictly limited to: ${PRODUCT_CATEGORIES_FOR_AI.join(", ")}`;
    const genAI = new GoogleGenerativeAI(apiKey);
    const model = genAI.getGenerativeModel({model: "gemini-3.1-pro-preview"});
    const result = await model.generateContent([prompt, imagePart]);
    const responseText = result.response.text();
    let suggestions;
    try {
      const jsonMatch = responseText.match(/{[\s\S]*}/);
      if (jsonMatch) suggestions = JSON.parse(jsonMatch[0]);
      else throw new Error("No JSON object found in the response.");
    } catch (parseError) {
      logger.error("Failed to parse Gemini visual search response as JSON:", responseText, parseError);
      throw new HttpsError("internal", "Could not process image search results (format error).");
    }
    if (!suggestions.category || !PRODUCT_CATEGORIES_FOR_AI.includes(suggestions.category)) {
      logger.warn(`Gemini suggested invalid or missing category '${suggestions.category}' from image, defaulting to 'Other'. Response: ${responseText}`);
      suggestions.category = "Other";
    }
    if (!suggestions.searchQuery) {
      logger.warn(`Gemini did not provide a search query. Response: ${responseText}`);
      suggestions.searchQuery = "";
    }
    return {suggestions};
  } catch (error: any) {
    logger.error("Error in visualSearch:", error);
    if (error instanceof HttpsError) throw error;
    if (error.message && (error.message.includes("quota") || error.message.includes("RESOURCE_EXHAUSTED") || error.message.includes("API key not valid"))) {
      throw new HttpsError("resource-exhausted", `AI service error: ${error.message}. Please try again later.`);
    }
    if (axios.isAxiosError(error)) {
      logger.error("Axios error fetching image for visual search:", error.response?.status, error.response?.data);
      throw new HttpsError("internal", "Network error fetching image.");
    }
    throw new HttpsError("internal", "Could not process the image search due to an internal error.");
  }
}

export async function parseSearchQuery(data: any) {
  const {query} = data;
  if (!query || typeof query !== "string" || query.trim().length === 0) {
    throw new HttpsError("invalid-argument", "A non-empty 'query' string is required.");
  }
  const apiKey = GEMINI_API_KEY.value();
  if (!apiKey) {
    throw new HttpsError("internal", "AI service is not configured correctly.");
  }

  try {
    const genAI = new GoogleGenerativeAI(apiKey);
    const model = genAI.getGenerativeModel({model: "gemini-3.1-pro-preview"});

    const schema = {
      type: SchemaType.OBJECT,
      properties: {
        searchQuery: {
          type: SchemaType.STRING,
          description: "The main search term or item description, e.g., 'red shirt', 'iPhone 12'.",
        },
        filters: {
          type: SchemaType.OBJECT,
          description: "Extracted filters. Omit any filter that is not mentioned.",
          properties: {
            category: {
              type: SchemaType.STRING,
              description: `The single most-likely category. Must be one of: ${PRODUCT_CATEGORIES_FOR_AI.join(", ")}.`,
              enum: PRODUCT_CATEGORIES_FOR_AI,
              nullable: true,
            },
            condition: {
              type: SchemaType.STRING,
              description: `The item condition. Must be one of: ${PRODUCT_CONDITIONS_FOR_AI.join(", ")}.`,
              enum: PRODUCT_CONDITIONS_FOR_AI,
              nullable: true,
            },
            minPrice: {
              type: SchemaType.NUMBER,
              description: "The minimum price, e.g., 'over $50' means 50.",
              nullable: true,
            },
            maxPrice: {
              type: SchemaType.NUMBER,
              description: "The maximum price, e.g., 'under $100' means 100.",
              nullable: true,
            },
          },
        },
      },
      required: ["searchQuery", "filters"],
    } as any;

    const prompt = `Analyze the user's search query: "${query}".
        Parse it into a structured JSON object according to the provided schema.
        - "searchQuery" should be the core item (e.g., 'lathe machine', 'blue t-shirt').
        - "filters" should only contain constraints explicitly mentioned (like price, category, or condition).
        - If a filter is not mentioned, omit it from the 'filters' object.
        - For 'category' and 'condition', only use the exact values provided in the schema's 'enum' list.
        - Extract prices like 'under 50' as 'maxPrice: 50' or 'over 100' as 'minPrice: 100'.
        - If no specific item is mentioned but filters are, use an empty string for "searchQuery".`;

    const result = await model.generateContent({
      contents: [{role: "user", parts: [{text: prompt}]}],
      generationConfig: {
        responseMimeType: "application/json",
        responseSchema: schema,
      },
    });

    const responseText = result.response.text();
    logger.log("Gemini parsed search query response:", responseText);

    let parsedJson;
    try {
      parsedJson = JSON.parse(responseText);
    } catch (e) {
      logger.error("Failed to parse Gemini JSON response:", e, responseText);
      throw new HttpsError("internal", "AI failed to return valid JSON.");
    }

    return {parsedSearch: parsedJson};
  } catch (error: any) {
    logger.error("Error in parseSearchQuery:", error);
    if (error.response?.candidates?.[0]?.finishReason) {
      logger.error("Gemini Finish Reason:", error.response.candidates[0].finishReason);
    }
    throw new HttpsError("internal", "Failed to parse search query with AI.");
  }
}

export async function askGemini(data: any) {
  const {prompt} = data;
  if (!prompt) throw new HttpsError("invalid-argument", "A non-empty 'prompt' is required.");
  const apiKey = GEMINI_API_KEY.value();
  if (!apiKey) throw new HttpsError("internal", "AI service API key not configured.");
  try {
    const genAI = new GoogleGenerativeAI(apiKey);
    const model = genAI.getGenerativeModel({model: "gemini-2.5-flash"});
    const result = await model.generateContent(prompt);
    const response = result.response;
    const text = response.text();
    if (!text) {
      logger.warn("Gemini returned an empty response for prompt:", prompt, "Full response:", JSON.stringify(result.response));
      throw new HttpsError("internal", "AI model returned an empty response.");
    }
    return {reply: text};
  } catch (error: any) {
    logger.error("Error calling Gemini in askGemini:", error);
    if (
      error.message &&
            (error.message.includes("quota") ||
                error.message.includes("RESOURCE_EXHAUSTED") ||
                error.message.includes("API key not valid"))
    ) {
      throw new HttpsError("resource-exhausted", `AI service error: ${error.message}. Please try again later.`);
    }
    throw new HttpsError(
      "internal",
      "Failed to process request with AI model."
    );
  }
}
