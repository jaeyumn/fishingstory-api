package kr.fishingstory.catchreport.query.dto;

import java.util.List;

public record SpeciesCatalogItem(long speciesId, String name, String standardName,
        String scientificName, String waterType, String familyName, String familyScientificName,
        String genusName, String genusScientificName, String entryKind, Long parentSpeciesId,
        List<String> aliases) {
    public record Row(long speciesId, String name, String standardName, String scientificName,
            String waterType, String familyName, String familyScientificName, String genusName,
            String genusScientificName, String entryKind, Long parentSpeciesId) {
        public SpeciesCatalogItem withAliases(List<String> aliases) {
            return new SpeciesCatalogItem(speciesId, name, standardName, scientificName, waterType,
                    familyName, familyScientificName, genusName, genusScientificName, entryKind,
                    parentSpeciesId, List.copyOf(aliases));
        }
    }
    public record Alias(long speciesId, String alias) { }
    public record Description(String identification, String habitat, String distribution,
            String fishingNotes, String similarSpecies) { }
    public record Regulation(long regulationId, String regulationType, String verificationStatus,
            String summary, String region, String periodDescription, java.math.BigDecimal minimumValue,
            String unit, String exceptions, String legalBasis, String sourceUrl,
            java.time.LocalDate checkedOn, java.time.LocalDate effectiveFrom,
            java.time.LocalDate effectiveUntil) { }
}
