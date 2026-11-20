package com.open.spring.scraper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import com.open.spring.scraper.dto.ScrapeField;
import com.open.spring.scraper.dto.ScrapeRequest;
import com.open.spring.scraper.dto.ScrapeResponse;
import com.open.spring.scraper.security.UrlSafetyValidator;

@Service
public class GenericScraperService {
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int MAX_REDIRECTS = 5;

    private final UrlSafetyValidator urlSafetyValidator;

    public GenericScraperService(UrlSafetyValidator urlSafetyValidator) {
        this.urlSafetyValidator = urlSafetyValidator;
    }

    public ScrapeResponse run(ScrapeRequest request) {
        long startedAt = System.currentTimeMillis();
        List<Map<String, Object>> allItems = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ScrapeRequest.LimitsConfig limits = request.getLimits();
        int maxPages = clamp(limits.getMaxPages(), 1, 100);
        int maxItems = clamp(limits.getMaxItems(), 1, 5000);
        int maxConcurrency = clamp(limits.getMaxConcurrency(), 1, 16);

        List<String> pageUrls = resolvePageUrls(request, maxPages, errors);
        if (pageUrls.isEmpty()) {
            pageUrls = List.of(request.getUrl());
        }

        ExecutorService pageExecutor = Executors.newFixedThreadPool(Math.min(pageUrls.size(), maxConcurrency));
        try {
            List<CompletableFuture<List<Map<String, Object>>>> pageFutures = pageUrls.stream()
                    .map(pageUrl -> CompletableFuture.supplyAsync(
                            () -> scrapeSinglePage(pageUrl, request, maxConcurrency, maxItems, errors),
                            pageExecutor
                    ))
                    .toList();

            for (CompletableFuture<List<Map<String, Object>>> future : pageFutures) {
                if (allItems.size() >= maxItems) {
                    break;
                }
                List<Map<String, Object>> pageItems = future.join();
                for (Map<String, Object> item : pageItems) {
                    if (allItems.size() >= maxItems) {
                        break;
                    }
                    allItems.add(item);
                }
            }
        } finally {
            shutdown(pageExecutor);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("durationMs", System.currentTimeMillis() - startedAt);
        meta.put("pagesVisited", pageUrls.size());
        meta.put("itemCount", allItems.size());
        meta.put("errors", errors);

        return new ScrapeResponse(allItems, meta);
    }

    private List<Map<String, Object>> scrapeSinglePage(
            String pageUrl,
            ScrapeRequest request,
            int maxConcurrency,
            int maxItems,
            List<String> errors
    ) {
        try {
            Document pageDoc = getDoc(pageUrl, request);
            Elements rows = pageDoc.select(request.getItemsSelector());
            if (rows.isEmpty()) {
                return Collections.emptyList();
            }

            List<Element> rowList = new ArrayList<>(rows);
            if (looksLikeHeaderRow(rowList.get(0))) {
                rowList = rowList.subList(1, rowList.size());
            }

            List<Map<String, Object>> baseItems = new ArrayList<>();
            for (Element row : rowList) {
                if (baseItems.size() >= maxItems) {
                    break;
                }
                Map<String, Object> item = extractFields(row, request.getFields(), pageUrl);
                if (item.isEmpty() || shouldSkip(item, request.getFilters())) {
                    continue;
                }
                baseItems.add(item);
            }

            if (request.getDetail() == null || request.getDetail().getFields().isEmpty() || baseItems.isEmpty()) {
                return baseItems;
            }

            ExecutorService detailExecutor = Executors.newFixedThreadPool(Math.min(baseItems.size(), maxConcurrency));
            try {
                List<CompletableFuture<Map<String, Object>>> detailFutures = baseItems.stream()
                        .map(item -> CompletableFuture.supplyAsync(
                                () -> enrichWithDetails(item, request, pageUrl, errors),
                                detailExecutor
                        ))
                        .toList();
                return detailFutures.stream().map(CompletableFuture::join).collect(Collectors.toList());
            } finally {
                shutdown(detailExecutor);
            }
        } catch (IOException e) {
            errors.add("Page failed " + pageUrl + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, Object> enrichWithDetails(
            Map<String, Object> item,
            ScrapeRequest request,
            String pageUrl,
            List<String> errors
    ) {
        String detailUrl = resolveDetailUrl(item, request, pageUrl);
        if (detailUrl == null || detailUrl.isBlank()) {
            return item;
        }

        try {
            Document detailDoc = getDoc(detailUrl, request);
            Map<String, Object> detailValues = extractFields(detailDoc, request.getDetail().getFields(), detailUrl);
            item.putAll(detailValues);
        } catch (IOException e) {
            errors.add("Detail failed " + detailUrl + ": " + e.getMessage());
        }
        return item;
    }

    private String resolveDetailUrl(Map<String, Object> item, ScrapeRequest request, String pageUrl) {
        Object explicitLink = item.get("link");
        if (explicitLink instanceof String link && !link.isBlank()) {
            return toAbsoluteUrl(pageUrl, link);
        }
        return null;
    }

    private Map<String, Object> extractFields(Element container, Map<String, ScrapeField> fields, String pageUrl) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ScrapeField> entry : fields.entrySet()) {
            ScrapeField field = entry.getValue();
            if (field == null || field.getSelector() == null || field.getSelector().isBlank()) {
                continue;
            }

            Elements selected = container.select(field.getSelector());
            String mode = field.getMode() == null ? "text" : field.getMode().trim().toLowerCase();
            switch (mode) {
                case "texts" -> result.put(entry.getKey(), selected.eachText());
                case "attr" -> {
                    Element first = selected.first();
                    if (first != null) {
                        String attrName = field.getAttr() == null || field.getAttr().isBlank() ? "href" : field.getAttr();
                        String attrValue = first.attr(attrName).trim();
                        result.put(entry.getKey(), "href".equalsIgnoreCase(attrName) ? toAbsoluteUrl(pageUrl, attrValue) : attrValue);
                    } else {
                        result.put(entry.getKey(), "");
                    }
                }
                default -> {
                    Element first = selected.first();
                    result.put(entry.getKey(), first == null ? "" : first.text().trim());
                }
            }
        }
        return result;
    }

    private List<String> resolvePageUrls(ScrapeRequest request, int maxPages, List<String> errors) {
        ScrapeRequest.PaginationConfig pagination = request.getPagination();
        if (pagination == null || pagination.getType() == null || pagination.getType().isBlank()) {
            return List.of(request.getUrl());
        }

        String type = pagination.getType().trim();
        try {
            return switch (type) {
                case "optionDataDirectUrl" -> fromOptionDataDirectUrl(request, pagination, maxPages);
                case "nextLink" -> fromNextLink(request, pagination, maxPages);
                case "queryParamPages" -> fromQueryParam(request, pagination, maxPages);
                default -> List.of(request.getUrl());
            };
        } catch (IOException e) {
            errors.add("Pagination parse failed: " + e.getMessage());
            return List.of(request.getUrl());
        }
    }

    private List<String> fromOptionDataDirectUrl(
            ScrapeRequest request,
            ScrapeRequest.PaginationConfig pagination,
            int maxPages
    ) throws IOException {
        Document first = getDoc(request.getUrl(), request);
        String selector = pagination.getSelector() == null || pagination.getSelector().isBlank()
                ? "option[name=page]" : pagination.getSelector();
        String attr = pagination.getAttr() == null || pagination.getAttr().isBlank()
                ? "data-direct-url" : pagination.getAttr();

        Set<String> urls = new LinkedHashSet<>();
        urls.add(request.getUrl());
        for (Element option : first.select(selector)) {
            String rel = option.attr(attr).trim();
            if (!rel.isBlank()) {
                urls.add(toAbsoluteUrl(request.getUrl(), rel));
            }
            if (urls.size() >= maxPages) {
                break;
            }
        }
        return new ArrayList<>(urls);
    }

    private List<String> fromNextLink(
            ScrapeRequest request,
            ScrapeRequest.PaginationConfig pagination,
            int maxPages
    ) throws IOException {
        String selector = pagination.getSelector() == null || pagination.getSelector().isBlank()
                ? "a[rel=next]" : pagination.getSelector();
        String attr = pagination.getAttr() == null || pagination.getAttr().isBlank() ? "href" : pagination.getAttr();

        Set<String> pages = new LinkedHashSet<>();
        String current = request.getUrl();
        for (int i = 0; i < maxPages && current != null; i++) {
            pages.add(current);
            Document doc = getDoc(current, request);
            Element next = doc.selectFirst(selector);
            if (next == null) {
                break;
            }
            String nextHref = next.attr(attr).trim();
            if (nextHref.isBlank()) {
                break;
            }
            String resolved = toAbsoluteUrl(current, nextHref);
            if (pages.contains(resolved)) {
                break;
            }
            current = resolved;
        }
        return new ArrayList<>(pages);
    }

    private List<String> fromQueryParam(
            ScrapeRequest request,
            ScrapeRequest.PaginationConfig pagination,
            int maxPages
    ) {
        String queryParam = pagination.getQueryParam() == null || pagination.getQueryParam().isBlank()
                ? "page" : pagination.getQueryParam();
        int startPage = pagination.getStartPage() == null ? 1 : Math.max(1, pagination.getStartPage());
        int endPage = pagination.getEndPage() == null
                ? startPage + maxPages - 1
                : Math.max(startPage, pagination.getEndPage());
        endPage = Math.min(endPage, startPage + maxPages - 1);

        List<String> pages = new ArrayList<>();
        for (int p = startPage; p <= endPage; p++) {
            pages.add(withQueryParam(request.getUrl(), queryParam, String.valueOf(p)));
        }
        return pages;
    }

    // Every fetch (next-page links, detail links, redirect targets) comes from the scraped page,
    // not the user, so each hop is re-checked; otherwise a public page could steer us to internal hosts.
    private Document getDoc(String url, ScrapeRequest request) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            try {
                urlSafetyValidator.validateTargetUrl(current);
            } catch (IllegalArgumentException e) {
                throw new IOException("Blocked " + current + ": " + e.getMessage());
            }

            Connection connection = Jsoup.connect(current)
                    .userAgent(DEFAULT_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(clamp(request.getLimits().getTimeoutMs(), 1000, 60000))
                    .followRedirects(false);

            Map<String, String> headers = request.getHeaders();
            if (headers != null) {
                headers.forEach(connection::header);
            }

            Connection.Response response = connection.execute();
            int status = response.statusCode();
            if (status < 300 || status >= 400 || !response.hasHeader("Location")) {
                return response.parse();
            }
            current = toAbsoluteUrl(current, response.header("Location"));
        }
        throw new IOException("Too many redirects: " + url);
    }

    private boolean shouldSkip(Map<String, Object> item, ScrapeRequest.FilterConfig filters) {
        if (filters == null || filters.getDeadlineField() == null || filters.getDeadlineField().isBlank()) {
            return false;
        }
        Object value = item.get(filters.getDeadlineField());
        if (!(value instanceof String deadline)) {
            return false;
        }
        if (filters.isSkipBlankDeadline() && deadline.isBlank()) {
            return true;
        }
        return filters.isSkipEndedDeadline() && deadline.equalsIgnoreCase(filters.getEndedValue());
    }

    // A genuine header row is built entirely of <th>; a data row that uses <th scope="row">
    // for one cell (a common accessibility pattern) still has <td> siblings and must not be
    // mistaken for one.
    private boolean looksLikeHeaderRow(Element row) {
        return row.select("td").isEmpty() && !row.select("th").isEmpty();
    }

    private String toAbsoluteUrl(String base, String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return pathOrUrl;
        }
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl;
        }

        URI baseUri = URI.create(base);
        if (pathOrUrl.startsWith("/")) {
            return baseUri.getScheme() + "://" + baseUri.getHost() + pathOrUrl;
        }
        if (pathOrUrl.startsWith("?")) {
            return baseUri.getScheme() + "://" + baseUri.getHost() + baseUri.getPath() + pathOrUrl;
        }
        return baseUri.resolve(pathOrUrl).toString();
    }

    private String withQueryParam(String url, String key, String value) {
        URI uri = URI.create(url);
        String existing = uri.getQuery();
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        String pair = key + "=" + encoded;
        String newQuery = existing == null || existing.isBlank() ? pair : existing + "&" + pair;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String scheme = uri.getScheme() + "://";
        String host = uri.getAuthority();
        return scheme + host + path + "?" + newQuery;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
