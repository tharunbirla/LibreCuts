//! Segment recognised text before applying presentation-only formatting.

const WORDS_PER_CAPTION: usize = 6;

/// Cut the original phrase into captions, sharing time by original byte length.
/// Keeping this calculation on the unformatted text preserves the default timing.
fn chunks(from: f64, to: f64, text: &str) -> Vec<(f64, f64, String)> {
    let words: Vec<&str> = text.split_whitespace().collect();
    if words.len() <= WORDS_PER_CAPTION {
        return vec![(from, to, words.join(" "))];
    }
    let pieces: Vec<String> = words
        .chunks(
            WORDS_PER_CAPTION.min(
                words
                    .len()
                    .div_ceil(words.len().div_ceil(WORDS_PER_CAPTION)),
            ),
        )
        .map(|chunk| chunk.join(" "))
        .collect();
    let letters: usize = pieces.iter().map(|piece| piece.len()).sum::<usize>().max(1);
    let mut at = from;
    pieces
        .into_iter()
        .map(|piece| {
            let length = (to - from) * piece.len() as f64 / letters as f64;
            let caption = (at, at + length, piece);
            at += length;
            caption
        })
        .collect()
}

/// Segment and time first; only then format each caption. Empty captions
/// resulting from stripping punctuation are omitted, without retiming others.
pub(crate) fn caption_chunks(
    from: f64, to: f64, text: &str, uppercase: bool, remove_punctuation: bool,
) -> Vec<(f64, f64, String)> {
    if text.trim().is_empty() {
        return Vec::new();
    }
    chunks(from, to, text)
        .into_iter()
        .filter_map(|(start, end, text)| {
            let formatted = format_caption(&text, uppercase, remove_punctuation);
            if formatted.trim().is_empty() { None } else { Some((start, end, formatted)) }
        })
        .collect()
}

fn removable(c: char) -> bool {
    matches!(c, '.' | ',' | '!' | '?' | ';' | ':')
}

/// Remove only the six marks named in the UI. Keep marks between digits,
/// and separate adjacent words instead of merging them. Apostrophes, hyphens,
/// emoji and other characters are untouched.
pub(crate) fn format_caption(text: &str, uppercase: bool, remove_punctuation: bool) -> String {
    let filtered: String = if remove_punctuation {
        let chars: Vec<char> = text.chars().collect();
        let mut out = String::with_capacity(text.len());
        let mut i = 0;
        while i < chars.len() {
            if !removable(chars[i]) {
                out.push(chars[i]);
                i += 1;
                continue;
            }
            let start = i;
            while i < chars.len() && removable(chars[i]) {
                i += 1;
            }
            if start > 0 && i < chars.len() {
                let left = chars[start - 1];
                let right = chars[i];
                if i == start + 1 && left.is_numeric() && right.is_numeric() {
                    out.push(chars[start]);
                } else if left.is_alphanumeric() && right.is_alphanumeric() {
                    out.push(' ');
                }
            }
        }
        out
    } else {
        text.to_owned()
    };
    if uppercase { filtered.to_uppercase() } else { filtered }
}

#[cfg(test)]
mod tests {
    use super::{caption_chunks, format_caption};

    #[test]
    fn defaults_preserve_text() {
        assert_eq!(format_caption("Olá, mundo!", false, false), "Olá, mundo!");
    }

    #[test]
    fn uppercase_handles_unicode() {
        assert_eq!(format_caption("ação e você", true, false), "AÇÃO E VOCÊ");
        assert_eq!(format_caption("Straße", true, false), "STRASSE");
    }

    #[test]
    fn removes_only_named_marks() {
        assert_eq!(format_caption("Oi, sim! Por quê? A:B; fim. — d'água 😊", false, true),
                   "Oi sim Por quê A B fim — d'água 😊");
    }

    #[test]
    fn preserves_numeric_separators() {
        assert_eq!(format_caption("1,5 e 2.500,00!", false, true), "1,5 e 2.500,00");
    }

    #[test]
    fn preserves_times_and_numbers_and_separates_words() {
        assert_eq!(format_caption("Às 12:30, 1,5 e 2.500,00!", false, true),
                   "Às 12:30 1,5 e 2.500,00");
        assert_eq!(format_caption("A:B ação:é A::B café,leite x:2 2:3", false, true),
                   "A B ação é A B café leite x 2 2:3");
        assert_eq!(format_caption("1.2 1,2 1!2 1?2 1;2 1:2", false, true),
                   "1.2 1,2 1!2 1?2 1;2 1:2");
    }

    #[test]
    fn formatting_never_retimes_nonempty_chunks() {
        // Both ß's expansion and punctuation removal change lengths. Seven
        // words force a split, with punctuation in both original chunks.
        let text = "Straße, ação! 12:30 A:B três quatro cinco sete";
        let original = caption_chunks(1.25, 9.75, text, false, false);
        assert!(original.len() > 1);
        assert_eq!(original[0].2, "Straße, ação! 12:30 A:B");
        assert_eq!(original[1].2, "três quatro cinco sete");
        for (upper, strip) in [(true, false), (false, true), (true, true)] {
            let formatted = caption_chunks(1.25, 9.75, text, upper, strip);
            assert_eq!(formatted.len(), original.len());
            for (before, after) in original.iter().zip(formatted.iter()) {
                assert_eq!((before.0, before.1), (after.0, after.1));
                assert_eq!(after.2, format_caption(&before.2, upper, strip));
            }
        }
    }

    #[test]
    fn default_segmentation_and_punctuation_only_chunks() {
        let original = caption_chunks(0.0, 9.0, "one   two three four five six seven", false, false);
        assert_eq!(original.len(), 2);
        assert_eq!(original[0].2, "one two three four");
        assert_eq!(original[1].2, "five six seven");
        assert!(caption_chunks(0.0, 1.0, "  ", false, false).is_empty());
        assert!(caption_chunks(0.0, 1.0, "...?!", false, true).is_empty());
        let with_punctuation = caption_chunks(0.0, 10.0, "... ... ... ... um dois três quatro", false, false);
        let stripped = caption_chunks(0.0, 10.0, "... ... ... ... um dois três quatro", false, true);
        assert_eq!(stripped.len(), 1);
        assert_eq!((stripped[0].0, stripped[0].1), (with_punctuation[1].0, with_punctuation[1].1));
    }

    #[test]
    fn options_combine_and_empty_remains_empty() {
        assert_eq!(format_caption("olá, mundo!", true, true), "OLÁ MUNDO");
        assert_eq!(format_caption("", true, true), "");
        assert_eq!(format_caption("...?!", true, true), "");
    }
}
