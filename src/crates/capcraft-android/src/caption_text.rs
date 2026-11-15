//! Formatting for newly generated caption text. Timestamps are unchanged.

/// Remove only the six punctuation marks named in the UI. Other characters,
/// including apostrophes, hyphens and emoji, are left untouched. Periods and
/// commas between digits are retained to avoid corrupting numeric values.
pub(crate) fn format_caption(text: &str, uppercase: bool, remove_punctuation: bool) -> String {
    let filtered: String = if remove_punctuation {
        let chars: Vec<char> = text.chars().collect();
        chars.iter().enumerate().filter_map(|(i, &c)| {
            let numeric_separator = matches!(c, '.' | ',') && i > 0 && i + 1 < chars.len()
                && chars[i - 1].is_numeric() && chars[i + 1].is_numeric();
            if matches!(c, '.' | ',' | '!' | '?' | ';' | ':') && !numeric_separator {
                None
            } else {
                Some(c)
            }
        }).collect()
    } else {
        text.to_owned()
    };
    if uppercase { filtered.to_uppercase() } else { filtered }
}

#[cfg(test)]
mod tests {
    use super::format_caption;

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
                   "Oi sim Por quê AB fim — d'água 😊");
    }

    #[test]
    fn preserves_numeric_separators() {
        assert_eq!(format_caption("1,5 e 2.500,00!", false, true), "1,5 e 2.500,00");
    }

    #[test]
    fn options_combine_and_empty_remains_empty() {
        assert_eq!(format_caption("olá, mundo!", true, true), "OLÁ MUNDO");
        assert_eq!(format_caption("", true, true), "");
        assert_eq!(format_caption("...?!", true, true), "");
    }
}
