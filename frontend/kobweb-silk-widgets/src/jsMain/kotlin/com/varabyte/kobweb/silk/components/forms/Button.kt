package com.varabyte.kobweb.silk.components.forms

import androidx.compose.runtime.*
import com.varabyte.kobweb.compose.css.*
import com.varabyte.kobweb.compose.dom.ElementRefScope
import com.varabyte.kobweb.compose.dom.clearFocus
import com.varabyte.kobweb.compose.foundation.layout.Box
import com.varabyte.kobweb.compose.foundation.layout.BoxScope
import com.varabyte.kobweb.compose.ui.Alignment
import com.varabyte.kobweb.compose.ui.Modifier
import com.varabyte.kobweb.compose.ui.modifiers.backgroundColor
import com.varabyte.kobweb.compose.ui.modifiers.cursor
import com.varabyte.kobweb.compose.ui.modifiers.onClick
import com.varabyte.kobweb.compose.ui.modifiers.onKeyDown
import com.varabyte.kobweb.compose.ui.modifiers.outline
import com.varabyte.kobweb.compose.ui.modifiers.role
import com.varabyte.kobweb.compose.ui.modifiers.tabIndex
import com.varabyte.kobweb.compose.ui.modifiers.userSelect
import com.varabyte.kobweb.silk.components.style.ComponentStyle
import com.varabyte.kobweb.silk.components.style.ComponentVariant
import com.varabyte.kobweb.silk.components.style.active
import com.varabyte.kobweb.silk.components.style.focus
import com.varabyte.kobweb.silk.components.style.hover
import com.varabyte.kobweb.silk.components.style.toModifier
import com.varabyte.kobweb.silk.theme.shapes.Rect
import com.varabyte.kobweb.silk.theme.shapes.clip
import com.varabyte.kobweb.silk.theme.toSilkPalette
import kotlinx.browser.document
import org.jetbrains.compose.web.css.*
import org.w3c.dom.HTMLElement

val ButtonStyle = ComponentStyle("silk-button") {
    val buttonColors = colorMode.toSilkPalette().button

    base {
        Modifier
            .backgroundColor(buttonColors.default)
            .clip(Rect(4.px))
            .outline(0.px) // Don't outline focused buttons - we'll use background color instead to indicate focus
            // No selecting text within buttons
            .userSelect(UserSelect.None)
    }

    hover {
        Modifier
            .backgroundColor(buttonColors.hover)
            .cursor(Cursor.Pointer)
    }

    focus {
        Modifier.backgroundColor(buttonColors.hover)
    }

    active {
        Modifier.backgroundColor(buttonColors.pressed)
    }
    (focus + active) {
        Modifier.backgroundColor(buttonColors.pressed)
    }
}

/**
 * An area which provides a SilkTheme-aware background color.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ComponentVariant? = null,
    ref: ElementRefScope<HTMLElement>? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        ButtonStyle.toModifier(variant)
            .role("button")
            .then(modifier)
            .onClick { evt ->
                document.activeElement?.clearFocus()
                onClick()
                evt.preventDefault()
            }
            .tabIndex(0) // Allow button to be tabbed to
            .onKeyDown { evt ->
                if (evt.isComposing) return@onKeyDown
                if (evt.key == "Enter") {
                    onClick()
                    evt.preventDefault()
                }
            },
        contentAlignment = Alignment.Center,
        ref = ref,
    ) {
        content()
    }
}