package pl.rafal.lifecycle.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.rafal.lifecycle.domain.RecapSlide
import pl.rafal.lifecycle.domain.YearRecap
import java.time.LocalDate

/** Baner w Trendach (widok roku). */
@Composable
fun YearRecapBanner(year: Int, onOpen: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5E5CE6), Color(0xFF0A84FF))))
            .clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✨", fontSize = 28.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Twój rok $year w Dobie", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("Sen, kilometry, miejsca, nastrój — w kilku kartach", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun YearRecapScreen(year: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val slides by produceState<List<RecapSlide>?>(null, year) { value = YearRecap.build(ctx, year) }
    val list = slides
    Box(Modifier.fillMaxSize().background(Color(0xFF14142B))) {
        if (list == null) {
            Text("Przeglądam Twój rok…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else if (list.isEmpty()) {
            Text(
                "Brak danych z roku $year.", color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center,
            )
        } else {
            val pager = rememberPagerState { list.size + 1 }
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                if (page < list.size) {
                    SlidePage(list[page], pager.currentPage == page)
                } else {
                    SummaryPage(year, list, pager.currentPage == page) {
                        scope.launch {
                            val f = YearRecap.shareImage(ctx, year, list)
                            shareFile(ctx, f, "image/png", "Mój rok $year w Dobie")
                        }
                    }
                }
            }
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(list.size + 1) { i ->
                    Box(
                        Modifier.size(width = if (i == pager.currentPage) 18.dp else 6.dp, height = 6.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.4f))
                    )
                }
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 4.dp)) {
            Icon(Icons.Filled.Close, "Zamknij", tint = Color.White)
        }
    }
}

@Composable
private fun SlidePage(s: RecapSlide, active: Boolean) {
    val emoji = remember { Animatable(0.3f) }
    val text = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            emoji.snapTo(0.3f); text.snapTo(0f)
            launch { emoji.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
            text.animateTo(1f, tween(700, delayMillis = 150))
        }
    }
    val base = Color(s.color)
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(base, base.copy(alpha = 0.55f), Color(0xFF14142B)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.emoji, fontSize = 84.sp, modifier = Modifier.scale(emoji.value))
            Spacer(Modifier.height(18.dp))
            Text(s.title, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleLarge, modifier = Modifier.alpha(text.value), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                s.big, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                modifier = Modifier.alpha(text.value).scale(0.85f + 0.15f * text.value),
            )
            Spacer(Modifier.height(14.dp))
            if (s.sub.isNotBlank()) {
                Text(
                    s.sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center, modifier = Modifier.alpha(text.value),
                )
            }
        }
    }
}

@Composable
private fun SummaryPage(year: Int, slides: List<RecapSlide>, active: Boolean, onShare: () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(active) { if (active) { a.snapTo(0f); a.animateTo(1f, tween(600)) } }
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF2C2A7A), Color(0xFF0A84FF)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(28.dp).alpha(a.value)) {
            Text("Mój rok $year", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(16.dp))
            slides.drop(1).take(10).forEach { s ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(s.big, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(s.title, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onShare,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF2C2A7A)),
            ) {
                Icon(Icons.Filled.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Udostępnij")
            }
        }
    }
}

fun recapYearFor(periodStart: LocalDate): Int = periodStart.year
