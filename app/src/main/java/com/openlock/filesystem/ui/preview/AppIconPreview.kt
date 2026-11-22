package com.openlock.filesystem.ui.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.openlock.filesystem.R
import com.openlock.filesystem.ui.theme.MyApplicationTheme

@Composable
fun AdaptiveIconCanvas(
    modifier: Modifier = Modifier,
    isMonochrome: Boolean = false,
) {
    Box(
        modifier = modifier.size(108.dp)
    ) {
        if (!isMonochrome) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_background),
                contentDescription = "Background",
                modifier = Modifier.fillMaxSize()
            )
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "Foreground",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF1E293B)
            ) {}
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                contentDescription = "Monochrome",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Preview(name = "App Icon Preview", showBackground = true, widthDp = 420, heightDp = 280)
@Composable
fun AppIconShowcasePreview() {
    MyApplicationTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F172A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Open FS • Launcher Icon",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Adaptive File System & Storage Icon",
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Squircle / Rounded (Standard Launcher, e.g. Samsung / Nothing)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.5.dp, Color(0x4038BDF8), RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.wrapContentSize(unbounded = true, align = Alignment.Center)) {
                                AdaptiveIconCanvas()
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Rounded", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelSmall)
                    }

                    // 2. Circle (Google Pixel / Moto / Stock Android)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color(0x4038BDF8), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.wrapContentSize(unbounded = true, align = Alignment.Center)) {
                                AdaptiveIconCanvas()
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Pixel Circle", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelSmall)
                    }

                    // 3. Monochrome Themed Icon (Android 13+ Material You)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color(0x4094A3B8), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.wrapContentSize(unbounded = true, align = Alignment.Center)) {
                                AdaptiveIconCanvas(isMonochrome = true)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Themed (M3)", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelSmall)
                    }

                    // 4. Full 108dp Canvas (Raw Vector)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0x25FFFFFF), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.wrapContentSize(unbounded = true, align = Alignment.Center)) {
                                AdaptiveIconCanvas()
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Canvas", color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
