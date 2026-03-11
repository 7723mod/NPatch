package org.lsposed.npatch.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.page.manage.AppManageBody
import org.lsposed.npatch.ui.page.manage.AppManageFab
import org.lsposed.npatch.ui.page.manage.ModuleManageBody
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TabRow

@Composable
fun ManageScreen(navigator: Navigator) {
    val scope = rememberCoroutineScope()
    val tabTitles = listOf(stringResource(R.string.apps), stringResource(R.string.modules))

    val pagerState = rememberPagerState(pageCount = { tabTitles.size })
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.screen_manage),
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            if (pagerState.currentPage == 0) AppManageFab(navigator)
        },
        popupHost = {}
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .fillMaxSize()
        ) {
            TabRow(
                tabs = tabTitles,
                selectedTabIndex = pagerState.currentPage,
                onTabSelected = {
                    scope.launch { pagerState.animateScrollToPage(it) }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> AppManageBody(navigator)
                    1 -> ModuleManageBody()
                }
            }
        }
    }
}
