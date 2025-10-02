package eu.nurkert.neverUp2Late.update.suggestion;

import java.util.List;

/**
 * Represents a potential update source that can be linked to a managed plugin.
 */
public record PluginLinkSuggestion(String provider,
                                   String title,
                                   String url,
                                   String description,
                                   List<String> highlights) {
}
