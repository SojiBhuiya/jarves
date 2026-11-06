package com.sojibhuiya.jarves

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvesApp()
        }
    }
}

@Composable
private fun JarvesApp() {
    val background = Brush.verticalGradient(
        colors = listOf(Color(0xFF071321), Color(0xFF02060D))
    )
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF02060D)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "JARVES",
                    color = Color(0xFFEAF7FF),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 5.sp
                )
                Spacer(modifier = Modifier.height(36.dp))
                Surface(
                    modifier = Modifier.height(150.dp),
                    shape = CircleShape,
                    color = Color(0xFF0D2940),
                    shadowElevation = 18.dp
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("◉", color = Color(0xFF55D9FF), fontSize = 54.sp)
                    }
                }
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = "Your assistant is getting ready.",
                    color = Color(0xFFB8C9D8),
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Foundation build · Voice features not connected yet",
                    color = Color(0xFF71869A),
                    fontSize = 12.sp
                )
            }
        }
    }
}
