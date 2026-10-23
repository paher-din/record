package com.paherdin.record2.records

internal object FinanceSeeds {
    const val ACCOUNT_ID = "00000000-0000-4000-8000-000000000001"
    val account = FinanceAccount(ACCOUNT_ID, "日常账户", 1)
    val categories = listOf(
        FinanceCategory("00000000-0000-4000-8000-000000000101", "餐饮", FinanceDirection.EXPENSE, 1),
        FinanceCategory("00000000-0000-4000-8000-000000000102", "出行", FinanceDirection.EXPENSE, 1),
        FinanceCategory("00000000-0000-4000-8000-000000000103", "购物", FinanceDirection.EXPENSE, 1),
        FinanceCategory("00000000-0000-4000-8000-000000000104", "居家", FinanceDirection.EXPENSE, 1),
        FinanceCategory("00000000-0000-4000-8000-000000000105", "学习", FinanceDirection.EXPENSE, 1),
        FinanceCategory("00000000-0000-4000-8000-000000000106", "其他支出", FinanceDirection.EXPENSE, 1),
        FinanceCategory("00000000-0000-4000-8000-000000000107", "生活费", FinanceDirection.INCOME, 1),
        FinanceCategory("00000000-0000-4000-8000-000000000108", "工资", FinanceDirection.INCOME, 1),
        FinanceCategory("00000000-0000-4000-8000-000000000109", "其他收入", FinanceDirection.INCOME, 1),
    )
}
