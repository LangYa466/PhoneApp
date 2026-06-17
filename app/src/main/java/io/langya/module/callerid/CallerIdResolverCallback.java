package io.langya.module.callerid;

public interface CallerIdResolverCallback {
    /**
     * @param displayName  contact/identification name (or null when unknown)
     * @param fromContacts true if the result came from the system contacts provider
     */
    void onResult(String displayName, boolean fromContacts);
}
