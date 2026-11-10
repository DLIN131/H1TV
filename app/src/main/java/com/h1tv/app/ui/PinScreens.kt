package com.h1tv.app.ui

import android.app.Activity
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.h1tv.app.lock.PinLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PIN 相關畫面的用途 */
enum class PinMode {
    /** 第一次開 App：問要不要設定 */
    FirstRun,
    /** 啟動時解鎖（可以按「忘記 PIN 碼」） */
    Unlock,
    /** 從設定開啟 PIN */
    Setup,
    /** 變更 PIN：先輸入目前的 */
    Change,
    /** 產生新的救援碼：先輸入目前的 PIN */
    NewCode,
    /** 關閉 PIN：先輸入目前的 */
    Disable,
}

private sealed interface Step {
    data object Intro : Step
    data object Verify : Step
    data object Recovery : Step
    data object NewPin : Step
    data class Confirm(val first: String) : Step
    data class ShowCode(val code: String) : Step
}

/**
 * PIN 的整個流程（輸入、確認、顯示救援碼…）。
 * onFinish：流程完成；onCancel：使用者按「取消」或返回鍵（null = 不能取消）。
 */
@Composable
fun PinFlow(mode: PinMode, onFinish: () -> Unit, onCancel: (() -> Unit)? = null) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var step by remember {
        mutableStateOf(
            when (mode) {
                PinMode.FirstRun -> Step.Intro
                PinMode.Setup -> Step.NewPin
                else -> Step.Verify
            }
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 透過救援碼進來的，設好新 PIN 後要換一組新的救援碼 */
    var viaRecovery by remember { mutableStateOf(false) }

    // 解鎖畫面按返回 = 離開 App；其他流程按返回 = 取消
    BackHandler {
        when {
            step is Step.Recovery -> { step = Step.Verify; error = null }
            // 第一次設定時按返回，回到「要不要設定」的問題
            mode == PinMode.FirstRun && step !is Step.Intro && step !is Step.ShowCode -> { step = Step.Intro; error = null }
            onCancel != null -> onCancel()
            else -> activity.finish()
        }
    }

    fun verify(input: String, recovery: Boolean) {
        busy = true
        scope.launch {
            // PBKDF2 在電視盒子上要幾百毫秒，不要卡住畫面
            val r = withContext(Dispatchers.Default) {
                if (recovery) PinLock.checkRecovery(input) else PinLock.checkPin(input)
            }
            busy = false
            when (r) {
                PinLock.Result.OK -> {
                    error = null
                    if (recovery) {
                        viaRecovery = true
                        step = Step.NewPin
                    } else when (mode) {
                        PinMode.Change -> step = Step.NewPin
                        PinMode.NewCode -> step = Step.ShowCode(withContext(Dispatchers.Default) { PinLock.regenerateRecoveryCode() })
                        PinMode.Disable -> {
                            PinLock.disable()
                            onFinish()
                        }
                        else -> onFinish()
                    }
                }
                PinLock.Result.WRONG -> error = if (recovery) "救援碼不正確" else "PIN 碼不正確"
                PinLock.Result.LOCKED_OUT -> error = null
            }
        }
    }

    fun save(pin: String) {
        busy = true
        scope.launch {
            val code = withContext(Dispatchers.Default) {
                val first = PinLock.setPin(pin)
                // 用過的救援碼可能被別人看到，換一組新的
                first ?: if (viaRecovery) PinLock.regenerateRecoveryCode() else null
            }
            busy = false
            if (code != null) step = Step.ShowCode(code) else onFinish()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = step) {
            Step.Intro -> IntroStep(
                onSet = { step = Step.NewPin },
                onSkip = {
                    PinLock.prompted = true
                    onFinish()
                },
            )
            Step.Verify -> EntryStep(
                key = "verify",
                title = if (mode == PinMode.Unlock) "請輸入 PIN 碼" else "請先輸入目前的 PIN 碼",
                subtitle = null,
                length = PinLock.PIN_LENGTH,
                error = error,
                busy = busy,
                onSubmit = { verify(it, recovery = false) },
                actions = buildList {
                    if (mode == PinMode.Unlock) add("忘記 PIN 碼？" to { step = Step.Recovery; error = null })
                    if (onCancel != null) add("取消" to onCancel)
                },
            )
            Step.Recovery -> EntryStep(
                key = "recovery",
                title = "輸入救援碼",
                subtitle = "設定 PIN 碼時畫面上顯示的 8 位數字",
                length = PinLock.RECOVERY_LENGTH,
                error = error,
                busy = busy,
                onSubmit = { verify(it, recovery = true) },
                actions = listOf("返回" to { step = Step.Verify; error = null }),
                footer = "連救援碼也找不到的話：到電視的「設定 → 應用程式 → H1TV → 清除資料」，" +
                    "PIN 碼會一起清掉（收藏和觀看紀錄也會清除）",
            )
            Step.NewPin -> EntryStep(
                key = "new",
                title = if (viaRecovery || mode == PinMode.Change) "設定新的 PIN 碼" else "設定 PIN 碼",
                subtitle = "請輸入 ${PinLock.PIN_LENGTH} 位數字，每次開啟 App 時都要輸入",
                length = PinLock.PIN_LENGTH,
                error = error,
                busy = busy,
                onSubmit = {
                    error = null
                    step = Step.Confirm(it)
                },
                actions = when {
                    mode == PinMode.FirstRun -> listOf("返回" to { step = Step.Intro; error = null })
                    onCancel != null && !viaRecovery -> listOf("取消" to onCancel)
                    else -> emptyList()
                },
            )
            is Step.Confirm -> EntryStep(
                key = "confirm",
                title = "再輸入一次確認",
                subtitle = null,
                length = PinLock.PIN_LENGTH,
                error = error,
                busy = busy,
                onSubmit = {
                    if (it == s.first) save(it)
                    else {
                        error = "兩次輸入的不一樣，請重新設定"
                        step = Step.NewPin
                    }
                },
                actions = listOf("重新輸入" to { step = Step.NewPin; error = null }),
            )
            is Step.ShowCode -> ShowCodeStep(s.code, onDone = onFinish)
        }
    }
}

@Composable
private fun IntroStep(onSet: () -> Unit, onSkip: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { first.requestFocus() }
    }
    Column(Modifier.width(620.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("要設定啟動 PIN 碼嗎？", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Text(
            "設定後，每次開啟 App（或離開超過 1 分鐘再回來）都要輸入 4 位數 PIN 碼。\n" +
                "之後也可以在左側選單的「設定」開啟、變更或關閉。",
            color = Gray,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton(onClick = onSet, modifier = Modifier.focusRequester(first)) { Text("設定 PIN 碼") }
            TvButton(onClick = onSkip) { Text("不用了，直接使用") }
        }
    }
}

/** 標題＋圓點＋數字鍵盤。遙控器的數字鍵也可以直接輸入，刪除鍵退一格 */
@Composable
private fun EntryStep(
    key: String,
    title: String,
    subtitle: String?,
    length: Int,
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    actions: List<Pair<String, () -> Unit>>,
    footer: String? = null,
) {
    var entered by remember(key) { mutableStateOf("") }
    var lockoutMs by remember { mutableLongStateOf(PinLock.lockoutRemainingMs()) }
    val firstKey = remember(key) { FocusRequester() }

    // 輸錯太多次的倒數
    LaunchedEffect(error, busy) {
        lockoutMs = PinLock.lockoutRemainingMs()
        while (lockoutMs > 0) {
            delay(1000)
            lockoutMs = PinLock.lockoutRemainingMs()
        }
    }
    LaunchedEffect(key) {
        delay(100)
        runCatching { firstKey.requestFocus() }
    }
    // 輸入錯誤後清空，重新輸入
    LaunchedEffect(error) { if (error != null) entered = "" }

    val disabled = busy || lockoutMs > 0
    fun press(d: Char) {
        if (disabled || entered.length >= length) return
        entered += d
        if (entered.length == length) {
            val value = entered
            entered = ""
            onSubmit(value)
        }
    }
    fun backspace() {
        if (!disabled) entered = entered.dropLast(1)
    }

    Column(
        Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (val code = e.nativeKeyEvent.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press('0' + (code - KeyEvent.KEYCODE_0)); true }
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { press('0' + (code - KeyEvent.KEYCODE_NUMPAD_0)); true }
                KeyEvent.KEYCODE_DEL -> { backspace(); true }
                else -> false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = Gray, fontSize = 15.sp)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(length) { i ->
                if (length > 4 && i == length / 2) Text("-", color = Gray, fontSize = 18.sp)
                Box(
                    Modifier
                        .size(18.dp)
                        .then(
                            if (i < entered.length) Modifier.background(Color.White, CircleShape)
                            else Modifier.border(2.dp, Gray, CircleShape)
                        )
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                lockoutMs > 0 -> "輸錯太多次，請等 ${(lockoutMs + 999) / 1000} 秒後再試"
                busy -> "檢查中…"
                else -> error ?: " "
            },
            color = if (lockoutMs > 0 || error != null) Accent else Gray,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(12.dp))
        // 1 2 3 / 4 5 6 / 7 8 9 / ⌫ 0
        val rows = listOf("123", "456", "789")
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { d ->
                    PadKey(
                        label = d.toString(),
                        onClick = { press(d) },
                        modifier = if (d == '1') Modifier.focusRequester(firstKey) else Modifier,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PadKey("⌫", onClick = ::backspace)
            PadKey("0", onClick = { press('0') })
            Spacer(Modifier.width(76.dp))
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                actions.forEach { (label, action) -> TvButton(onClick = action) { Text(label) } }
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(16.dp))
            Text(footer, color = Gray, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.width(560.dp))
        }
    }
}

@Composable
private fun PadKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvButton(onClick = onClick, modifier = modifier.width(76.dp)) {
        Text(label, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun ShowCodeStep(code: String, onDone: () -> Unit) {
    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { done.requestFocus() }
    }
    Column(Modifier.width(640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("請記下你的救援碼", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text(
            PinLock.formatRecovery(code),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .background(CardBg, RoundedCornerShape(12.dp))
                .padding(horizontal = 36.dp, vertical = 14.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "忘記 PIN 碼時，在解鎖畫面按「忘記 PIN 碼？」輸入這組數字就能重設。\n" +
                "這組救援碼只會顯示這一次，請抄下來或用手機拍照保存。",
            color = Gray,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        TvButton(onClick = onDone, modifier = Modifier.focusRequester(done)) { Text("我記下了") }
    }
}

/** 左側選單「設定」：PIN 碼管理 */
@Composable
fun SettingsScreen() {
    var mode by remember { mutableStateOf<PinMode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(PinLock.enabled) }

    val m = mode
    if (m != null) {
        PinFlow(
            mode = m,
            onFinish = {
                message = when (m) {
                    PinMode.Setup -> "已開啟啟動 PIN 碼"
                    PinMode.Change -> "PIN 碼已變更"
                    PinMode.NewCode -> "已產生新的救援碼，舊的已失效"
                    PinMode.Disable -> "已關閉啟動 PIN 碼"
                    else -> null
                }
                enabled = PinLock.enabled
                mode = null
            },
            onCancel = { mode = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("設定")
        Column(Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) {
            Text("啟動 PIN 碼", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (enabled) "已開啟：開啟 App 或離開超過 1 分鐘再回來時要輸入 PIN 碼"
                else "未開啟：任何人打開 App 都能直接使用",
                color = Gray,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (enabled) {
                    TvButton(onClick = { message = null; mode = PinMode.Change }) { Text("變更 PIN 碼") }
                    TvButton(onClick = { message = null; mode = PinMode.NewCode }) { Text("產生新的救援碼") }
                    TvButton(onClick = { message = null; mode = PinMode.Disable }) { Text("關閉 PIN 碼") }
                } else {
                    TvButton(onClick = { message = null; mode = PinMode.Setup }) { Text("設定 PIN 碼") }
                }
            }
            message?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = Accent, fontSize = 15.sp)
            }
        }
    }
}
