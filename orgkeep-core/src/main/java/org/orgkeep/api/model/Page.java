package org.orgkeep.api.model;

import java.util.List;

/**
 * A page of a paginated list, with the total number of elements that match the request (its search and filters
 * applied), whatever {@code first} and {@code max} were: a list screen can show "page 3 of 7" or a total count
 * without reading every page.
 */
public record Page<T>(List<T> items, long total) { }
