package com.example.mallar

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mallar.ui.screens.*
import com.example.mallar.ui.theme.MallARTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {

        // ✅ تثبيت Splash
        val splashScreen = installSplashScreen()

        // ✅ متغير يتحكم في اختفاء السبلاش
        var isReady = false

        super.onCreate(savedInstanceState)

        // ✅ خلي الـ Splash تفضل لحد ما Compose تجهز
        splashScreen.setKeepOnScreenCondition {
            !isReady
        }

        enableEdgeToEdge()

        setContent {
            MallARTheme {

                // ✅ أول ما Compose تشتغل -> نخفي السبلاش
                LaunchedEffect(Unit) {
                    isReady = true
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    MallARNavGraph(applicationContext)
                }
            }
        }
    }
}

@Composable
fun MallARNavGraph(context: Context) {
    val navController = rememberNavController()
    val prefs: SharedPreferences =
        remember { context.getSharedPreferences("mallar_prefs", Context.MODE_PRIVATE) }

    val isFirstLaunch = remember {
        mutableStateOf(prefs.getBoolean("is_first_launch", true))
    }

    fun checkPermissionsGranted(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        )
        return cameraPermission == PackageManager.PERMISSION_GRANTED
    }

    NavHost(
        navController = navController,
        startDestination = "splash"
    ) {
        composable("splash") {
            SplashScreen(
                isFirstLaunch = isFirstLaunch.value,
                onStartClick = {
                    if (isFirstLaunch.value) {
                        navController.navigate("welcome") {
                            popUpTo("splash") { inclusive = true }
                        }
                    } else {
                        if (checkPermissionsGranted()) {
                            navController.navigate("logo_scan") {
                                popUpTo("splash") { inclusive = true }
                            }
                        } else {
                            navController.navigate("permissions") {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    }
                }
            )
        }

        composable("welcome") {
            WelcomeScreen(
                onPhoneAuthClick = {
                    navController.navigate("phone_auth")
                },
                onSkipClick = {
                    isFirstLaunch.value = false
                    prefs.edit().putBoolean("is_first_launch", false).apply()

                    if (checkPermissionsGranted()) {
                        navController.navigate("logo_scan") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    } else {
                        navController.navigate("permissions") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    }
                }
            )
        }

        composable("phone_auth") {
            PhoneAuthScreen(
                onBackClick = { navController.popBackStack() },
                onSendClick = {
                    navController.navigate("otp_verify")
                },
                onSkipClick = {
                    isFirstLaunch.value = false
                    prefs.edit().putBoolean("is_first_launch", false).apply()

                    if (checkPermissionsGranted()) {
                        navController.navigate("logo_scan") {
                            popUpTo("phone_auth") { inclusive = true }
                        }
                    } else {
                        navController.navigate("permissions") {
                            popUpTo("phone_auth") { inclusive = true }
                        }
                    }
                }
            )
        }

        composable("otp_verify") {
            OtpVerifyScreen(
                onBackClick = { navController.popBackStack() },
                onVerifyClick = {
                    isFirstLaunch.value = false
                    prefs.edit().putBoolean("is_first_launch", false).apply()

                    if (checkPermissionsGranted()) {
                        navController.navigate("logo_scan") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    } else {
                        navController.navigate("permissions") {
                            popUpTo("welcome") { inclusive = true }
                        }
                    }
                }
            )
        }

        composable("permissions") {
            PermissionsScreen(
                onContinueClick = {
                    navController.navigate("logo_scan") {
                        popUpTo("permissions") { inclusive = true }
                    }
                }
            )
        }

        composable("logo_scan") {
            LogoScanScreen(
                onSettingsClick = {
                    navController.navigate("settings")
                },
                onStoreSelected = { useAr ->
                    if (useAr) {
                        navController.navigate("ar_navigation")
                    } else {
                        navController.navigate("static_map")
                    }
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onLogoutClick = {
                    navController.navigate("welcome") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable("store_search") {
            StoreSearchScreen(
                onStoreClick = { place ->
                    NavigationState.selectedPlace = place
                    navController.navigate("store_detail")
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("store_detail") {
            val place = NavigationState.selectedPlace
            if (place != null) {
                StoreDetailScreen(
                    place = place,
                    onBackClick = { navController.popBackStack() },
                    onStartNavigation = { useAr ->
                        if (useAr) {
                            navController.navigate("ar_navigation")
                        } else {
                            navController.navigate("static_map")
                        }
                    }
                )
            } else {
                navController.popBackStack()
            }
        }

        composable("ar_navigation") {
            ArNavigationScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("static_map") {
            StaticMapScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}