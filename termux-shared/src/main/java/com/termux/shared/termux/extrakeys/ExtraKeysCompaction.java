package com.termux.shared.termux.extrakeys;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the <em>compact</em> extra-keys layout used in landscape: the panel's rows are folded into
 * one or two rows instead of being shown as-is.
 *
 * <p>The stored layout ({@code extra-keys} / {@code extra-keys-session}) is never touched — this is a
 * pure, on-the-fly re-arrangement of the same {@link ExtraKeyButton} matrix, so every binding (tap,
 * four swipes, popup, special buttons, auto-repeat) survives the fold untouched.
 *
 * <p>Rules:
 * <ul>
 *   <li>1–3 source rows → <b>one</b> target row (all rows merged);</li>
 *   <li>4 source rows → <b>two</b> target rows: rows 0–1 give the first, rows 2–3 the second;</li>
 *   <li>more than 4 rows (only reachable by hand-editing {@code extra-keys}) → two target rows, the
 *       upper half of the rows and the lower half.</li>
 * </ul>
 *
 * <p>Inside a group of rows the order is given by {@link Mode}: {@link Mode#ROWS} walks the group in
 * normal reading order, {@link Mode#COLUMNS} walks it column by column (top to bottom inside each
 * column).
 *
 * <p><b>The fold is purely positional.</b> The content of a cell is never inspected: a cell that is
 * empty in the stored layout stays an (empty) cell in the folded one, and a cell that carries only
 * swipe bindings stays exactly where the reading order puts it. The folded panel therefore holds the
 * same cells as the stored one — the same count, the same order — just spread over fewer rows.
 */
public final class ExtraKeysCompaction {

    /** Order in which the keys of a row group are laid out in the compact row. */
    public enum Mode {
        /** First all elements of the first row, then those of the second, and so on. */
        ROWS,
        /** Column by column: top to bottom inside a column, then the next column. */
        COLUMNS
    }

    private ExtraKeysCompaction() {}

    /** Preference value for {@link Mode#ROWS}. */
    public static final String MODE_VALUE_ROWS = "rows";
    /** Preference value for {@link Mode#COLUMNS}. */
    public static final String MODE_VALUE_COLUMNS = "columns";

    /**
     * Resolve a stored preference value to a {@link Mode}, falling back to {@link Mode#ROWS} for
     * anything unknown (a hand-edited preference file must not break the panel).
     */
    @NonNull
    public static Mode modeFromPreferenceValue(@Nullable String value) {
        return MODE_VALUE_COLUMNS.equals(value) ? Mode.COLUMNS : Mode.ROWS;
    }

    /**
     * Number of rows the panel has once folded: 1 for up to three source rows, 2 from four rows up.
     *
     * @param sourceRows rows in the stored layout
     */
    public static int targetRows(int sourceRows) {
        if (sourceRows <= 1) return Math.max(sourceRows, 0);
        return sourceRows <= 3 ? 1 : 2;
    }

    /**
     * Row count to use for the panel height: the folded count when compaction is active, otherwise
     * the stored count. Shared by {@code ExtraKeysView.reload()} (which builds the grid) and
     * {@code TermuxActivity.setTerminalToolbarHeight()} (which sizes the panel) so the two can never
     * disagree.
     */
    public static int effectiveRowCount(int sourceRows, boolean compact) {
        return compact ? targetRows(sourceRows) : sourceRows;
    }

    /**
     * Group boundaries inside the source matrix: {@code {0, rows}} for a single group, or
     * {@code {0, half, rows}} for two groups (upper and lower half of the rows).
     */
    @NonNull
    public static int[] rowGroupBounds(int rows, int groups) {
        if (groups <= 1 || rows <= 1) return new int[] { 0, Math.max(rows, 0) };
        int half = (rows + 1) / 2;   // 4 -> 2+2, 5 -> 3+2, 6 -> 3+3
        return new int[] { 0, half, rows };
    }

    /**
     * Fold the source matrix into the compact one.
     *
     * @param matrix the stored layout
     * @param mode   order of keys inside a folded row
     * @return the folded matrix, or {@code null} when there is nothing to fold (the caller then keeps
     *         the source matrix). {@code null} is returned when the layout already has at most the
     *         target number of rows, and when no real button survives the fold — an empty panel would
     *         be worse than an unfolded one.
     */
    @Nullable
    public static ExtraKeyButton[][] compact(@NonNull ExtraKeyButton[][] matrix, @NonNull Mode mode) {
        int sourceRows = matrix.length;
        int target = targetRows(sourceRows);
        if (target >= sourceRows) return null;

        int[] bounds = rowGroupBounds(sourceRows, target);
        int maxCols = maximumLength(matrix);
        List<ExtraKeyButton[]> folded = new ArrayList<>(bounds.length - 1);

        for (int group = 0; group + 1 < bounds.length; group++) {
            int from = bounds[group];
            int to = bounds[group + 1];
            List<ExtraKeyButton> sequence = new ArrayList<>();

            if (mode == Mode.ROWS) {
                for (int row = from; row < to; row++) {
                    for (ExtraKeyButton button : matrix[row]) {
                        sequence.add(button);
                    }
                }
            } else {
                for (int col = 0; col < maxCols; col++) {
                    for (int row = from; row < to; row++) {
                        // A jagged row simply has no cell at this index; skipping it is a statement
                        // about the matrix shape, not about the content of a cell.
                        if (col < matrix[row].length) {
                            sequence.add(matrix[row][col]);
                        }
                    }
                }
            }

            if (!sequence.isEmpty()) {
                folded.add(sequence.toArray(new ExtraKeyButton[0]));
            }
        }

        if (folded.isEmpty()) return null;
        return folded.toArray(new ExtraKeyButton[0][]);
    }

    /** Longest row length of a matrix — mirrors {@code ExtraKeysView.maximumLength(Object[][])}. */
    private static int maximumLength(@NonNull ExtraKeyButton[][] matrix) {
        int max = 0;
        for (ExtraKeyButton[] row : matrix) max = Math.max(max, row.length);
        return max;
    }
}
