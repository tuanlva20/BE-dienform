package com.dienform.tool.dienformtudong.googleform.util;

import org.springframework.stereotype.Component;

/**
 * Utility class for data processing operations that can be reused across the application
 * This class provides methods for preprocessing Google Forms data and normalizing text for comparison
 */
@Component
public class DataProcessingUtils {
    
    /**
     * Preprocess data-params from Google Forms HTML
     * This method handles HTML entities, JSON syntax fixes, and prefix removal
     * 
     * @param dataParams The raw data-params string from Google Forms
     * @return Cleaned and processed data-params string
     */
    public static String preprocessDataParams(String dataParams) {
        if (dataParams == null) {
            return "";
        }
        
        // Remove prefix %.@. if present
        if (dataParams.startsWith("%.@.")) {
            dataParams = dataParams.substring(4);
        }

        // Replace HTML entities
        dataParams = dataParams.replace("&quot;", "\"");
        dataParams = dataParams.replace("&amp;", "&");
        dataParams = dataParams.replace("&lt;", "<");
        dataParams = dataParams.replace("&gt;", ">");

        // Fix common JSON issues
        dataParams = dataParams.replaceAll(",\\s*]", "]"); // Remove trailing commas in arrays
        dataParams = dataParams.replaceAll(",\\s*}", "}"); // Remove trailing commas in objects

        return dataParams;
    }
    
    /**
     * Normalize text for comparison by handling HTML entities, case, and whitespace
     * This method is useful for comparing option texts that may contain HTML entities
     * 
     * @param text The text to normalize
     * @return Normalized text (lowercase, trimmed, HTML entities decoded)
     */
    public static String normalizeForComparison(String text) {
        if (text == null) {
            return "";
        }
        
        // Decode HTML entities
        String decoded = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
        
        // Normalize case and whitespace
        return decoded.toLowerCase().trim();
    }
    
    /**
     * Normalize question title for comparison by removing extra whitespace and newlines
     * This method is specifically designed for question title comparison
     * 
     * @param title The question title to normalize
     * @return Normalized title
     */
    public static String normalizeQuestionTitle(String title) {
        if (title == null) {
            return "";
        }
        // Remove extra whitespace, newlines, and normalize spaces
        return title.replaceAll("\\s+", " ").trim();
    }
} 