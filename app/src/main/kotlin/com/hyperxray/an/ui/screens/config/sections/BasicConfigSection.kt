package com.hyperxray.an.ui.screens.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.hyperxray.an.R

/**
 * Basic configuration section containing filename field.
 */
@Composable
fun BasicConfigSection(
    filename: String,
    filenameErrorMessage: String?,
    onFilenameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        TextField(
            value = filename,
            onValueChange = onFilenameChange,
            label = { 
                Text(
                    stringResource(id = R.string.filename),
                    color = Color(0xFFB0B0B0)
                ) 
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent
            ),
            isError = filenameErrorMessage != null,
            supportingText = {
                filenameErrorMessage?.let { Text(it) }
            }
        )
    }
}











