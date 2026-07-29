package com.example.bilitranscript.theme

import androidx.compose.ui.graphics.Color

// =============================================================================
// Claude Mobile App Design Tokens
// Extracted from official Claude (Anthropic) mobile app screenshots
// Style: Warm cream background, flat/clean, no glassmorphism, terracotta accent
// =============================================================================

// --- Core palette ---

/** Warm cream/beige — app-level background */
val ClaudeBackground = Color(0xFFF8F5F0)

/** Pure white — content cards, chat bubbles, input fields */
val ClaudeSurface = Color(0xFFFFFFFF)

/** Claude's signature warm orange/terracotta — primary accent */
val ClaudeAccent = Color(0xFFC2742F)

/** Slightly lighter variant for hover / pressed states */
val ClaudeAccentLight = Color(0xFFD97706)

/** Very light tint of accent for subtle highlights */
val ClaudeAccentUltraLight = Color(0xFFFEF3E2)

// --- Text ---

/** Primary text — near-black, dark charcoal */
val ClaudeTextPrimary = Color(0xFF1A1A1A)

/** Secondary text — medium gray for labels, hints */
val ClaudeTextSecondary = Color(0xFF6B7280)

/** Tertiary text — disabled / very subtle captions */
val ClaudeTextTertiary = Color(0xFF9CA3AF)

/** Text on accent background (white) */
val ClaudeTextOnAccent = Color(0xFFFFFFFF)

// --- Borders & Dividers ---

/** Standard border / divider — light gray */
val ClaudeBorder = Color(0xFFE5E7EB)

/** Input field border — slightly warmer light gray */
val ClaudeInputBorder = Color(0xFFDEE2E6)

/** Focused input border — uses accent color */
val ClaudeInputFocusBorder = ClaudeAccent

// --- Semantic colors ---

/** Success / complete */
val ClaudeSuccess = Color(0xFF16A34A)

/** Warning / in-progress */
val ClaudeWarning = Color(0xFFF59E0B)

/** Error / failure */
val ClaudeError = Color(0xFFDC2626)

/** Info / neutral highlight */
val ClaudeInfo = Color(0xFF2563EB)

// --- Surface variants ---

/** Subtle gray surface — hover states, secondary cards */
val ClaudeSurfaceHover = Color(0xFFF3F4F6)

/** Muted surface — disabled containers */
val ClaudeSurfaceMuted = Color(0xFFF9FAFB)

/** Elevated surface — dropdowns, popovers */
val ClaudeSurfaceElevated = Color(0xFFFFFFFF)

// --- Navigation ---

/** Active nav item text */
val ClaudeNavActive = ClaudeAccent

/** Inactive nav item text */
val ClaudeNavInactive = ClaudeTextTertiary

// --- Bottom sheet / overlay ---

/** Scrim / overlay behind bottom sheets */
val ClaudeScrim = Color(0x40000000)

/** Bottom sheet handle color */
val ClaudeSheetHandle = Color(0xFFD1D5DB)

// --- Progress / Loading ---

/** Progress bar track */
val ClaudeProgressTrack = Color(0xFFE5E7EB)

/** Progress bar fill — uses accent */
val ClaudeProgressFill = ClaudeAccent
