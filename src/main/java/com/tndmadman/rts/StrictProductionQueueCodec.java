package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class StrictProductionQueueCodec {
    private static final Pattern JOB_ID = Pattern.compile("P[1-9][0-9]*");
    private static final int MAX_JOBS = 1024;
    private static final int MAX_ENCODED_LENGTH = 1_000_000;
    private static final int MAX_JOB_ID_LENGTH = 64;
    private static final int MAX_ITEM_ID_LENGTH = 128;
    private static final int MAX_UNIT_KEY_LENGTH = 256;
    private static final int MAX_BLOCKED_REASON_LENGTH = 256;
    private static final double MAX_DURATION_SECONDS = 365.0 * 24.0 * 60.0 * 60.0;

    private StrictProductionQueueCodec() { }

    static void readInto(String text, Base base) {
        readInto(text, base, "");
    }

    static void readInto(String text, Base base, String systemId) {
        if (base == null) throw new IllegalArgumentException("Base is required for production queue decoding.");
        DecodedProductionQueue decoded = decode(text, systemId, base.id);
        base.productionQueue.clear();
        base.productionQueue.addAll(decoded.jobs());
        base.nextProductionJobId = decoded.nextProductionJobId();
    }

    static DecodedProductionQueue decode(String text, String systemId, String baseId) {
        if (text == null || text.isEmpty() || "-".equals(text)) {
            return new DecodedProductionQueue(List.of(), 1);
        }
        if (text.length() > MAX_ENCODED_LENGTH) {
            throw error(systemId, baseId, 0, "queue payload exceeds " + MAX_ENCODED_LENGTH + " characters");
        }

        String[] rows = text.split("~", -1);
        if (rows.length > MAX_JOBS) {
            throw error(systemId, baseId, 0, "queue contains more than " + MAX_JOBS + " jobs");
        }

        List<ProductionJob> jobs = new ArrayList<>(rows.length);
        Set<String> ids = new HashSet<>();
        long nextProductionJobId = 1;
        for (int i = 0; i < rows.length; i++) {
            int rowIndex = i + 1;
            String[] columns = rows[i].split("\\^", -1);
            if (columns.length != 7 && columns.length != 8 && columns.length != 10 && columns.length != 13) {
                throw error(systemId, baseId, rowIndex,
                        "expected 7 or 8 columns for legacy rows, 10 columns for loadout rows, or 13 columns for quote rows, but found " + columns.length);
            }

            String id = required(columns[0], "job ID", systemId, baseId, rowIndex);
            validateText(id, MAX_JOB_ID_LENGTH, "job ID", systemId, baseId, rowIndex);
            long suffix = jobSuffix(id, systemId, baseId, rowIndex);
            if (!ids.add(id)) throw error(systemId, baseId, rowIndex, "duplicate job ID " + id);

            ProductionJobKind kind;
            try {
                kind = ProductionJobKind.valueOf(columns[1]);
            } catch (RuntimeException ex) {
                throw error(systemId, baseId, rowIndex, "unknown job kind " + printable(columns[1]));
            }

            String itemId = required(unclean(columns[2]), "item ID", systemId, baseId, rowIndex);
            validateText(itemId, MAX_ITEM_ID_LENGTH, "item ID", systemId, baseId, rowIndex);
            validateItem(kind, itemId, systemId, baseId, rowIndex);

            double duration = finiteNumber(columns[3], "duration", systemId, baseId, rowIndex);
            double remaining = finiteNumber(columns[4], "remaining", systemId, baseId, rowIndex);
            if (duration < 0 || duration > MAX_DURATION_SECONDS) {
                throw error(systemId, baseId, rowIndex,
                        "duration must be between 0 and " + (long)MAX_DURATION_SECONDS + " seconds");
            }
            if (remaining < 0 || remaining > duration) {
                throw error(systemId, baseId, rowIndex, "remaining must be between 0 and duration");
            }

            boolean resourcesReserved = parseFlag(columns[5], systemId, baseId, rowIndex);
            String reservedUnitKey = unclean(columns[6]);
            validateText(reservedUnitKey, MAX_UNIT_KEY_LENGTH, "reserved unit key", systemId, baseId, rowIndex);
            validateReservedUnitKey(kind, reservedUnitKey, systemId, baseId, rowIndex);

            String blockedReason = columns.length >= 8 ? unclean(columns[7]) : "";
            validateText(blockedReason, MAX_BLOCKED_REASON_LENGTH, "blocked reason", systemId, baseId, rowIndex);
            String loadoutId = columns.length >= 10 ? unclean(columns[8]) : "";
            String subjectUnitKey = columns.length >= 10 ? unclean(columns[9]) : "";
            validateText(loadoutId, MAX_ITEM_ID_LENGTH, "loadout ID", systemId, baseId, rowIndex);
            validateText(subjectUnitKey, MAX_UNIT_KEY_LENGTH, "subject unit key", systemId, baseId, rowIndex);
            validateLoadoutFields(kind, itemId, loadoutId, subjectUnitKey, systemId, baseId, rowIndex);
            String sourceLoadoutId = columns.length >= 13 ? unclean(columns[10]) : "";
            int quoteVersion = columns.length >= 13
                    ? nonNegativeInteger(columns[11], "refit quote version", systemId, baseId, rowIndex) : 0;
            List<Cost> reservedCost;
            try { reservedCost = columns.length >= 13 ? RefitQuote.decodeCosts(columns[12]) : List.of(); }
            catch (IllegalArgumentException ex) { throw error(systemId, baseId, rowIndex, ex.getMessage()); }
            validateQuoteFields(kind, itemId, sourceLoadoutId, quoteVersion, reservedCost,
                    systemId, baseId, rowIndex);

            ProductionJob job = new ProductionJob(id, kind, itemId, duration, remaining,
                    resourcesReserved, reservedUnitKey);
            job.blockedReason = blockedReason;
            job.loadoutId = kind == ProductionJobKind.SHIP && loadoutId.isBlank()
                    ? WeaponRules.defaultLoadoutId(itemId) : loadoutId;
            job.subjectUnitKey = subjectUnitKey;
            job.sourceLoadoutId = sourceLoadoutId;
            job.refitQuoteVersion = quoteVersion;
            job.reservedCost = reservedCost;
            if (columns.length < 13) RefitQuote.migrateLegacy(job);
            jobs.add(job);
            nextProductionJobId = Math.max(nextProductionJobId, suffix + 1);
        }
        return new DecodedProductionQueue(List.copyOf(jobs), nextProductionJobId);
    }

    private static void validateItem(ProductionJobKind kind, String itemId, String systemId,
                                     String baseId, int rowIndex) {
        boolean known = switch (kind) {
            case SHIP -> Rules.SHIPS.containsKey(itemId);
            case STATION_PACKAGE -> Rules.BASES.containsKey(itemId);
            case CRAFTABLE -> CraftingRules.item(itemId) != null;
            case RESEARCH -> ResearchRules.topic(itemId) != null;
            case REFIT -> Rules.SHIPS.containsKey(itemId);
        };
        if (!known) {
            throw error(systemId, baseId, rowIndex,
                    "unknown " + kind.name().toLowerCase(Locale.ROOT) + " item ID " + itemId);
        }
    }

    private static void validateLoadoutFields(ProductionJobKind kind, String itemId, String loadoutId,
                                              String subjectUnitKey, String systemId, String baseId, int rowIndex) {
        if (kind == ProductionJobKind.SHIP || kind == ProductionJobKind.REFIT) {
            String resolvedId = loadoutId.isBlank() && kind == ProductionJobKind.SHIP
                    ? WeaponRules.defaultLoadoutId(itemId) : loadoutId;
            ShipLoadoutDefinition loadout = WeaponRules.findLoadout(resolvedId);
            if (loadout == null || !itemId.equals(loadout.hullId())) {
                throw error(systemId, baseId, rowIndex, "unknown or mismatched loadout ID " + printable(resolvedId));
            }
            if (kind == ProductionJobKind.REFIT) {
                if (subjectUnitKey.isBlank()) {
                    throw error(systemId, baseId, rowIndex, "refit subject unit key is required");
                }
                validateUnitKey(subjectUnitKey, "refit subject unit key", systemId, baseId, rowIndex);
            }
            if (kind == ProductionJobKind.SHIP && !subjectUnitKey.isBlank()) {
                throw error(systemId, baseId, rowIndex, "subject unit key is only valid for refit jobs");
            }
            return;
        }
        if (!loadoutId.isBlank() || !subjectUnitKey.isBlank()) {
            throw error(systemId, baseId, rowIndex, "loadout and subject fields are only valid for ship or refit jobs");
        }
    }

    private static void validateQuoteFields(ProductionJobKind kind, String itemId, String sourceLoadoutId,
                                            int quoteVersion, List<Cost> reservedCost,
                                            String systemId, String baseId, int rowIndex) {
        validateText(sourceLoadoutId, MAX_ITEM_ID_LENGTH, "source loadout ID", systemId, baseId, rowIndex);
        if (kind != ProductionJobKind.REFIT) {
            if (!sourceLoadoutId.isBlank() || quoteVersion != 0 || !reservedCost.isEmpty()) {
                throw error(systemId, baseId, rowIndex, "refit quote fields are only valid for refit jobs");
            }
            return;
        }
        if (quoteVersion == 0) {
            if (!sourceLoadoutId.isBlank() || !reservedCost.isEmpty()) {
                throw error(systemId, baseId, rowIndex, "legacy refit rows cannot contain quote fields");
            }
            return;
        }
        if (quoteVersion != RefitQuote.CURRENT_VERSION) {
            throw error(systemId, baseId, rowIndex, "unsupported refit quote version " + quoteVersion);
        }
        ShipLoadoutDefinition source = WeaponRules.findLoadout(sourceLoadoutId);
        if (source == null || !itemId.equals(source.hullId())) {
            throw error(systemId, baseId, rowIndex, "unknown or mismatched source loadout ID " + printable(sourceLoadoutId));
        }
    }

    private static int nonNegativeInteger(String value, String field, String systemId, String baseId, int rowIndex) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException ex) {
            throw error(systemId, baseId, rowIndex, field + " must be a non-negative integer");
        }
    }

    private static void validateReservedUnitKey(ProductionJobKind kind, String value, String systemId,
                                                String baseId, int rowIndex) {
        if (value.isBlank()) return;
        if (kind != ProductionJobKind.STATION_PACKAGE) {
            throw error(systemId, baseId, rowIndex,
                    "reserved unit key is only valid for station-package jobs");
        }
        validateUnitKey(value, "reserved unit key", systemId, baseId, rowIndex);
    }

    private static void validateUnitKey(String value, String field, String systemId, String baseId, int rowIndex) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw error(systemId, baseId, rowIndex, "invalid " + field + " " + printable(value));
        }
        String unitId = value.substring(colon + 1);
        for (int i = 0; i < unitId.length(); i++) {
            if (!Character.isDigit(unitId.charAt(i))) {
                throw error(systemId, baseId, rowIndex, "invalid " + field + " " + printable(value));
            }
        }
        try {
            Integer.parseInt(unitId);
        } catch (NumberFormatException ex) {
            throw error(systemId, baseId, rowIndex, "invalid " + field + " " + printable(value));
        }
    }

    private static boolean parseFlag(String value, String systemId, String baseId, int rowIndex) {
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw error(systemId, baseId, rowIndex, "resources-reserved flag must be 0 or 1");
    }

    private static double finiteNumber(String value, String field, String systemId, String baseId, int rowIndex) {
        final double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (RuntimeException ex) {
            throw error(systemId, baseId, rowIndex, field + " is not a number");
        }
        if (!Double.isFinite(parsed)) throw error(systemId, baseId, rowIndex, field + " must be finite");
        return parsed;
    }

    private static long jobSuffix(String id, String systemId, String baseId, int rowIndex) {
        if (!JOB_ID.matcher(id).matches()) {
            throw error(systemId, baseId, rowIndex, "job ID must match P<positive integer>");
        }
        try {
            long suffix = Long.parseLong(id.substring(1));
            if (suffix == Long.MAX_VALUE) throw new NumberFormatException();
            return suffix;
        } catch (NumberFormatException ex) {
            throw error(systemId, baseId, rowIndex, "job ID is too large");
        }
    }

    private static String required(String value, String field, String systemId, String baseId, int rowIndex) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            throw error(systemId, baseId, rowIndex, field + " is required");
        }
        return value;
    }

    private static void validateText(String value, int maxLength, String field, String systemId,
                                     String baseId, int rowIndex) {
        if (value.length() > maxLength) {
            throw error(systemId, baseId, rowIndex, field + " exceeds " + maxLength + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '|' || c == ',' || c == ';' || c == '^' || c == '~') {
                throw error(systemId, baseId, rowIndex, field + " contains an invalid character");
            }
        }
    }

    private static String unclean(String value) {
        return value == null || "-".equals(value) ? "" : value;
    }

    private static String printable(String value) {
        if (value == null || value.isBlank()) return "<blank>";
        return value.length() <= 48 ? value : value.substring(0, 45) + "...";
    }

    private static SnapshotDecodeException error(String systemId, String baseId, int rowIndex, String reason) {
        StringBuilder message = new StringBuilder("Snapshot rejected: malformed production queue");
        if (systemId != null && !systemId.isBlank()) message.append(" in system ").append(systemId);
        if (baseId != null && !baseId.isBlank()) message.append(" for base ").append(baseId);
        if (rowIndex > 0) message.append(", row ").append(rowIndex);
        message.append(" - ").append(reason).append('.');
        return new SnapshotDecodeException(message.toString());
    }
}

record DecodedProductionQueue(List<ProductionJob> jobs, long nextProductionJobId) { }

final class SnapshotDecodeException extends IllegalArgumentException {
    SnapshotDecodeException(String message) {
        super(message);
    }
}
