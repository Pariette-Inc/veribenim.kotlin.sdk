package com.veribenim.consent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veribenim.consent.ConsentCategory
import com.veribenim.consent.ConsentPreferences
import com.veribenim.consent.Veribenim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hazır rıza ekranı (Jetpack Compose).
 *
 * Kendi tasarımınızı çizmek isterseniz bunu hiç kullanmayın — [Veribenim]
 * tüm kararları verir. Bu "hızlı başlangıç" içindir.
 *
 * KVKK Rehberi No:69 Bölüm 7.3 gereği "Reddet" butonu "Kabul Et" ile aynı
 * görsel ağırlıkta ve aynı ekranda durur; gizlenemez, ikinci adıma atılamaz.
 *
 * Ağ çağrıları IO dispatcher'a alınır — [Veribenim] bloklayıcıdır ve kendi
 * thread politikasını dayatmaz.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(
    veribenim: Veribenim,
    accent: Color = Color(0xFF059669),
    onDecided: (ConsentPreferences) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showDetails by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(ConsentPreferences.DENIED) }

    fun decide(block: () -> Unit) {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            onDecided(veribenim.preferences)
        }
    }

    ModalBottomSheet(onDismissRequest = { /* rıza verilmeden kapatılamaz */ }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Gizlilik Tercihleriniz", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Text(
                "Uygulamayı geliştirmek ve size uygun içerik sunmak için cihazınızda " +
                    "tanımlayıcılar kullanıyoruz. Zorunlu olanlar dışındakiler yalnızca " +
                    "izninizle çalışır.",
                fontSize = 14.sp,
            )

            if (showDetails) {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    CategoryRow("İşlevsel", draft.functional, accent) {
                        draft = draft.copy(functional = it)
                    }
                    CategoryRow("Analitik", draft.analytics, accent) {
                        draft = draft.copy(analytics = it)
                    }
                    CategoryRow("Pazarlama", draft.marketing, accent) {
                        draft = draft.copy(marketing = it)
                    }
                    CategoryRow(
                        label = "Zorunlu",
                        checked = true,
                        accent = accent,
                        enabled = false,
                        subtitle = "Uygulamanın çalışması için gerekli — kapatılamaz.",
                        onChange = {},
                    )
                }
            }

            // Kabul ve Reddet aynı ağırlıkta — KVKK Rehberi No:69 Bölüm 7.3.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { decide { veribenim.rejectAll() } },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF1F5F9),
                        contentColor = Color(0xFF0F172A),
                    ),
                ) { Text("Reddet", fontWeight = FontWeight.SemiBold) }

                Button(
                    onClick = { decide { veribenim.accept(ConsentPreferences.GRANTED) } },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) { Text("Tümünü Kabul Et", fontWeight = FontWeight.SemiBold) }
            }

            TextButton(
                onClick = {
                    if (showDetails) decide { veribenim.accept(draft) } else showDetails = true
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (showDetails) "Seçimimi Kaydet" else "Tercihleri Yönet",
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CategoryRow(
    label: String,
    checked: Boolean,
    accent: Color,
    enabled: Boolean = true,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.sp, color = Color(0xFF94A3B8))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = accent),
        )
    }
}
