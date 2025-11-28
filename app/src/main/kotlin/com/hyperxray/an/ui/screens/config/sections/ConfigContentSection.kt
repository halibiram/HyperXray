package com.hyperxray.an.ui.screens.config.sections

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.hyperxray.an.R
import com.hyperxray.an.ui.util.bracketMatcherTransformation

/**
 * Configuration content section for raw JSON editor.
 */
@Composable
fun ConfigContentSection(
    configTextFieldValue: TextFieldValue,
    onConfigContentChange: (TextFieldValue) -> Unit,
    onAutoIndent: (String, Int) -> Pair<String, Int>,
    modifier: Modifier = Modifier
) {
    val isKeyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    
    TextField(
        value = configTextFieldValue,
        onValueChange = { newTextFieldValue ->
            val newText = newTextFieldValue.text
            val oldText = configTextFieldValue.text
            val cursorPosition = newTextFieldValue.selection.start

            if (newText.length == oldText.length + 1 &&
                cursorPosition > 0 &&
                newText[cursorPosition - 1] == '\n'
            ) {
                val pair = onAutoIndent(newText, cursorPosition - 1)
                onConfigContentChange(
                    TextFieldValue(
                        text = pair.first,
                        selection = TextRange(pair.second)
                    )
                )
            } else {
                onConfigContentChange(newTextFieldValue.copy(text = newText))
            }
        },
        visualTransformation = bracketMatcherTransformation(configTextFieldValue),
        label = { 
            Text(
                stringResource(R.string.content),
                color = Color(0xFFB0B0B0)
            ) 
        },
        modifier = modifier
            .padding(bottom = if (isKeyboardOpen) 0.dp else 0.dp)
            .fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.1.sp,
            color = Color(0xFFE0E0E0)
        ),
        keyboardOptions = KeyboardOptions.Default.copy(
            keyboardType = KeyboardType.Text
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent
        )
    )
}











