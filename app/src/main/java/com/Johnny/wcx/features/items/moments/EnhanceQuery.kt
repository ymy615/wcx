package com.Johnny.wcx.features.items.moments

import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "朋友圈查询增强", categories = ["朋友圈"], description = "显示本地仍有缓存的朋友圈；开启时使用微信原生个人相册，重新进入相册生效")
object EnhanceQuery : SwitchFeature(), WeDatabaseListenerApi.IQueryListener, IResolveDex {

    private const val TAG = "EnhanceQuery"

    // 8.0.79 routes profile thumbnails and the album to different data sources.
    // The native album still reads SnsInfo through the query listener below.
    // Resolve the album switch only: timeline/detail/publish have separate switches.
    private val methodUseFlutterAlbum by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("enableFlutterSNSPage", "com.tencent.mm.plugin.sns.router.SnsRouter")
            paramCount = 0
            returnType = "boolean"
        }
    }

    override fun onEnable() {
        if (!methodUseFlutterAlbum.isPlaceholder) {
            methodUseFlutterAlbum.hookBefore { result = false }
            WeLogger.i(TAG, "native album compatibility enabled")
        }
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onQuery(sql: String): String? {
        val rewritten = rewriteSql(sql)

        if (rewritten != sql) {
            WeLogger.i(TAG, "enhanced moments SQL query")
            return rewritten
        }

        return null
    }

    private fun rewriteSql(sql: String): String {
        // Count and row queries must agree or a cached row can exist while the
        // native album still considers the page empty. Keep unrelated SQL intact.
        if (!sql.startsWith("select *,rowid from SnsInfo", true) &&
            !sql.startsWith("select count(*) from SnsInfo", true)
        ) return sql

        var newSql = sql

        if (sql.contains("WHERE SnsInfo.userName=", false)) {
            newSql = sql
                .replace(SOURCE_TYPE_FILTER, SOURCE_TYPE_FILTER_ENHANCED)
                .replace("(snsId >= ", "(1=1 or snsId >= ")
                // WeChat can use stringSeq instead of numeric snsId for the
                // same oldest-visible boundary. Preserve < / <= pagination.
                .replace("(stringSeq >=", "(1=1 or stringSeq >=")
        }

        return newSql.replace("(sourceType & 2 != 0 )", "(1=1)")
    }

    private const val SOURCE_TYPE_FILTER =
        "(sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396,142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472,218,474,220,476,222,478))"

    private const val SOURCE_TYPE_FILTER_ENHANCED =
        "(sourceType in (0,2,8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,344,90,346,92,348,94,350,136,392,138,394,140,396,142,398,152,408,154,410,156,412,158,414,200,456,202,458,204,460,206,462,216,472,218,474,220,476,222,478))"
}
