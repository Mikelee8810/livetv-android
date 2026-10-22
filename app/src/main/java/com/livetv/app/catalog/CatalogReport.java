package com.livetv.app.catalog;

/* JADX INFO: loaded from: classes6.dex */
public final class CatalogReport {
    public final int adultExcluded;
    public final int duplicatesExcluded;
    public final int missingEpgIds;
    public final int missingLogos;
    public final int parsedEntries;
    public final int visibleEntries;

    public CatalogReport(int parsedEntries, int adultExcluded, int duplicatesExcluded, int visibleEntries, int missingLogos, int missingEpgIds) {
        this.parsedEntries = parsedEntries;
        this.adultExcluded = adultExcluded;
        this.duplicatesExcluded = duplicatesExcluded;
        this.visibleEntries = visibleEntries;
        this.missingLogos = missingLogos;
        this.missingEpgIds = missingEpgIds;
    }
}
