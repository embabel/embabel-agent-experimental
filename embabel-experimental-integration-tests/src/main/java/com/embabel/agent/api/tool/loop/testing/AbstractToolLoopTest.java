/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.api.tool.loop.testing;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.callback.*;
import com.embabel.agent.api.tool.callback.LogLevel;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Base class for ToolLoop integration tests.
 * Provides shared utilities for menu extraction, tool creation, and common types.
 */
public abstract class AbstractToolLoopTest {

    /**
     * Restaurant recommendation result type used across tests.
     */
    @JsonClassDescription("Restaurant recommendation based on menu analysis")
    public record RestaurantRecommendation(
        @JsonPropertyDescription("Name of recommended restaurant")
        String recommendedRestaurant,
        @JsonPropertyDescription("Why this restaurant was chosen")
        String reasoning,
        @JsonPropertyDescription("Notable dishes from the menu")
        List<String> notableDishes,
        @JsonPropertyDescription("Number of menus analyzed")
        int menusAnalyzed
    ) {}

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected static final int DEFAULT_TRUNCATION_LENGTH = 3000;

    /**
     * Hardcoded restaurant menu URLs for testing.
     * These are real AllMenus.com URLs that can be scraped with Jsoup.
     */
    protected static final Map<String, String> RESTAURANT_MENU_URLS = Map.of(
        "Serafina", "https://www.allmenus.com/ny/new-york/381986-serafina-upper-east-side/menu/",
        "Via Quadronno", "https://www.allmenus.com/ny/new-york/77238-via-quadronno/menu/",
        "Caravaggio", "https://www.allmenus.com/ny/new-york/27632-caravaggio/menu/"
    );

    /**
     * Extract menu data from a URL using Jsoup.
     * Looks for JSON-LD structured data containing menu information.
     *
     * @param url the menu page URL
     * @return extracted menu JSON or null if not found
     */
    protected String extractMenuJson(String url) {
        try {
            var doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(10000)
                .get();
            return doc.select("script[type=application/ld+json]")
                .stream()
                .map(Element::html)
                .map(String::trim)
                .filter(json -> json.contains("\"Menu\"") || json.contains("\"Restaurant\""))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to extract menu from {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Extract plain text menu content from a URL using Jsoup.
     * Falls back to text extraction if JSON-LD is not available.
     *
     * @param url the menu page URL
     * @return extracted menu text
     */
    protected String extractMenuText(String url) {
        try {
            var doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(10000)
                .get();
            // Try JSON-LD first
            var jsonLd = extractMenuJson(url);
            if (jsonLd != null) {
                return jsonLd;
            }
            // Fall back to menu section text
            var menuSection = doc.select(".menu-section, .menu-items, [class*=menu]");
            if (!menuSection.isEmpty()) {
                return menuSection.text();
            }
            // Last resort: body text (truncated)
            var bodyText = doc.body().text();
            return bodyText.length() > 5000 ? bodyText.substring(0, 5000) : bodyText;
        } catch (Exception e) {
            logger.warn("Failed to extract menu text from {}: {}", url, e.getMessage());
            return "Menu unavailable for " + url;
        }
    }

    /**
     * Create a tool for fetching a specific restaurant's menu.
     *
     * @param restaurantName the restaurant name (used in tool name)
     * @param menuUrl the URL to fetch menu from
     * @return a Tool that fetches and returns the menu
     */
    protected Tool createMenuTool(String restaurantName, String menuUrl) {
        var safeName = restaurantName.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
        var toolName = "fetch_" + safeName + "_menu";

        return Tool.create(
            toolName,
            "Fetch menu for " + restaurantName + " restaurant",
            input -> {
                var thread = Thread.currentThread().getName();
                logger.info("[{}] {} START: {}", thread, toolName, menuUrl);
                var start = System.currentTimeMillis();
                var result = extractMenuText(menuUrl);
                logger.info("[{}] {} END: {}ms", thread, toolName, System.currentTimeMillis() - start);
                return Tool.Result.text(result);
            }
        );
    }

    /**
     * Create tools for all hardcoded restaurants.
     *
     * @return list of menu-fetching tools
     */
    protected List<Tool> createAllMenuTools() {
        return RESTAURANT_MENU_URLS.entrySet().stream()
            .map(entry -> createMenuTool(entry.getKey(), entry.getValue()))
            .toList();
    }

    /**
     * Create a logging inspector with INFO level.
     */
    protected ToolLoopLoggingInspector createLoggingInspector() {
        return new ToolLoopLoggingInspector(
            LogLevel.INFO,
            LoggerFactory.getLogger(ToolLoopLoggingInspector.class)
        );
    }

    /**
     * Create a truncating transformer with default length and INFO level logging.
     */
    protected ToolResultTruncatingTransformer createTruncatingTransformer() {
        return createTruncatingTransformer(DEFAULT_TRUNCATION_LENGTH);
    }

    /**
     * Create a truncating transformer with INFO level logging.
     *
     * @param maxLength maximum result length before truncation
     */
    protected ToolResultTruncatingTransformer createTruncatingTransformer(int maxLength) {
        return new ToolResultTruncatingTransformer(
            maxLength,
            null, // uses default marker
            LogLevel.INFO,
            LoggerFactory.getLogger(ToolResultTruncatingTransformer.class)
        );
    }

    /**
     * Create a sliding window transformer with logging.
     *
     * @param maxMessages maximum number of messages to retain
     */
    protected LoggingSlidingWindowTransformer createSlidingWindowTransformer(int maxMessages) {
        return new LoggingSlidingWindowTransformer(maxMessages, logger);
    }

    /**
     * Sliding window transformer wrapper with logging to show when truncation occurs.
     */
    public static class LoggingSlidingWindowTransformer implements com.embabel.agent.api.tool.callback.ToolLoopTransformer {
        private final SlidingWindowTransformer delegate;
        private final int maxMessages;
        private final Logger log;

        public LoggingSlidingWindowTransformer(int maxMessages, Logger logger) {
            this.delegate = new SlidingWindowTransformer(maxMessages, true);
            this.maxMessages = maxMessages;
            this.log = logger;
        }

        @Override
        public @org.jetbrains.annotations.NotNull List<com.embabel.chat.Message> transformBeforeLlmCall(
                @org.jetbrains.annotations.NotNull BeforeLlmCallContext context) {
            var original = context.getHistory().size();
            var result = delegate.transformBeforeLlmCall(context);
            if (result.size() < original) {
                log.info("SlidingWindow [beforeLlmCall]: truncated {} -> {} messages (max={})",
                    original, result.size(), maxMessages);
            }
            return result;
        }

        @Override
        public @org.jetbrains.annotations.NotNull List<com.embabel.chat.Message> transformAfterIteration(
                @org.jetbrains.annotations.NotNull AfterIterationContext context) {
            var original = context.getHistory().size();
            var result = delegate.transformAfterIteration(context);
            if (result.size() < original) {
                log.info("SlidingWindow [afterIteration]: truncated {} -> {} messages (max={})",
                    original, result.size(), maxMessages);
            }
            return result;
        }
    }
}
