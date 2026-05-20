# Regex & PII Algorithm Parsing Skill

This skill documents regular expression specifications, checksum algorithms, and index-to-block coordinate mapping mechanisms used in **ZenScan** to detect sensitive PII texts (credit cards, emails, phone numbers).

---

## 1. PII Regex Definitions

ZenScan uses custom regular expressions optimized to scan flat OCR-recognized text streams:

* **Credit Cards**: `\b(?:\d[ -]*?){13,19}\b`
  * Matches numerical groups containing between 13 and 19 digits, allowing for optional spaces or dashes in between digits.
* **Email Addresses**: `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`
  * Matches standard RFC 5322 email patterns, ensuring valid domain name endings.
* **Phone Numbers**: `\+?\d{1,3}[-.\s]?\(?\d{1,4}\)?[-.\s]?\d{1,4}[-.\s]?\d{1,9}`
  * Robustly captures localized, standard, and international formats (e.g. +1, dashes, parentheses, or dots).

---

## 2. Card Validation Checksums (Luhn Algorithm)

To prevent marking random strings of 13-19 digits (like model serial numbers or package barcodes) as credit cards, ZenScan filters matches using the mathematical **Luhn Algorithm** (Mod 10):

### Implementation Pattern (from `PiiPatternMatcher.isValidLuhn`)
1. Wipes all non-digit characters (spaces, dashes) from the matched string.
2. Reverses the digit characters.
3. Multiplies every alternate digit (starting with the second digit from the right) by 2. If the product exceeds 9, subtracts 9.
4. Sums all digits together. If the sum modulo 10 is 0, the number represents a valid credit card layout.

```kotlin
fun isValidLuhn(number: String): Boolean {
    if (number.length < 13 || number.length > 19) return false
    var sum = 0
    var alternate = false
    for (i in number.indices.reversed()) {
        val digit = number[i] - '0'
        var n = digit
        if (alternate) {
            n *= 2
            if (n > 9) n -= 9
        }
        sum += n
        alternate = !alternate
    }
    return sum % 10 == 0
}
```

---

## 3. Global OCR String-to-Block Bounding Box Mapping

ML Kit's OCR returns separate blocks/lines of text with localized coordinates, but regex scans a concatenated, flat string of all found text. ZenScan implements a robust mapping algorithm to locate which bounding boxes correspond to the matching characters:

### The Mapping Logic
1. **Index Compilation**: Creates a list (`charToBlock`) that maps every single character in the concatenated text back to its parent `TextBlockBounds` object.
2. **Intersection Check**: Filters `charToBlock` for character indices that fall within the regex match's starting and ending indexes (`match.startIndex until match.endIndex`).
3. **Box Union**: Extracts distinct matching text blocks, then calculates the union envelope (`combinedRect`) across their bounding boxes by finding the minimum top/left and maximum bottom/right boundary bounds.

```kotlin
fun mapMatchesToRegions(
    matches: List<Pair<RegexMatch, String>>,
    textBlocks: List<TextBlockBounds>
): List<DetectedRegion> {
    val charToBlock = mutableListOf<Pair<Int, TextBlockBounds>>()
    var globalIndex = 0
    for (block in textBlocks) {
        for (i in block.text.indices) {
            charToBlock.add(globalIndex to block)
            globalIndex++
        }
        charToBlock.add(globalIndex to block)
        globalIndex++
    }

    return matches.map { (match, label) ->
        val matchedBlocks = charToBlock
            .filter { (idx, _) -> idx in match.startIndex until match.endIndex }
            .map { it.second }
            .distinct()

        val combinedRect = if (matchedBlocks.isEmpty()) {
            Rect(0, 0, 0, 0)
        } else {
            val first = matchedBlocks.first().boundingBox
            var left = first.left
            var top = first.top
            var right = first.right
            var bottom = first.bottom
            for (block in matchedBlocks) {
                val r = block.boundingBox
                left = minOf(left, r.left)
                top = minOf(top, r.top)
                right = maxOf(right, r.right)
                bottom = maxOf(bottom, r.bottom)
            }
            Rect(left, top, right, bottom)
        }

        DetectedRegion(
            boundingBox = combinedRect,
            label = label,
            type = RegionType.PII_TEXT
        )
    }
}
```

---

## 4. Visual Reference Locations
* Pattern scan engine: [PiiPatternMatcher.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/detection/PiiPatternMatcher.kt)
