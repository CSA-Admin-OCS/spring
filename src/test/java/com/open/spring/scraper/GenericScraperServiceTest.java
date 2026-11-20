package com.open.spring.scraper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.scraper.dto.ScrapeRequest;

import com.open.spring.scraper.security.UrlSafetyValidator;

/**
 * Covers looksLikeHeaderRow, which strips a real header row that slipped into the matched
 * items (e.g. an itemsSelector that also catches a <thead> row) without also stripping a
 * data row that happens to use <th scope="row"> for one cell, a common accessibility
 * pattern (see the Dallas College preset, where every row's name cell is a <th>).
 */
class GenericScraperServiceTest {

    private final GenericScraperService service = new GenericScraperService(new UrlSafetyValidator());

    private boolean looksLikeHeaderRow(Element row) {
        return ReflectionTestUtils.invokeMethod(service, "looksLikeHeaderRow", row);
    }

    private Element firstRow(String tableHtml) {
        return Jsoup.parse(tableHtml).selectFirst("tr");
    }

    @Test
    void aRowOfOnlyThCellsIsAHeaderRow() {
        Element row = firstRow("<table><tr><th>Award</th><th>Name</th></tr></table>");

        assertTrue(looksLikeHeaderRow(row));
    }

    @Test
    void aDataRowWithOneScopeRowThCellIsNotAHeaderRow() {
        Element row = firstRow(
            "<table><tr><td>$250</td><th scope=\"row\"><a href=\"/x\">Scholarship</a></th><td>11/01/2026</td></tr></table>"
        );

        assertFalse(looksLikeHeaderRow(row));
    }

    @Test
    void aPlainAllTdDataRowIsNotAHeaderRow() {
        Element row = firstRow("<table><tr><td>A</td><td>B</td></tr></table>");

        assertFalse(looksLikeHeaderRow(row));
    }

    @Test
    void aFollowUpFetchToAnInternalHostIsBlocked() {
        // next-page and detail links come from the scraped page, so they must pass the same check as the start url
        Throwable thrown = assertThrows(Throwable.class, () ->
            ReflectionTestUtils.invokeMethod(service, "getDoc", "http://169.254.169.254/latest/meta-data/", new ScrapeRequest()));

        assertTrue(thrown.getCause() instanceof java.io.IOException);
    }
}
