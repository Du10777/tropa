package io.github.du10777.tropa.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * "Apps without VPN": upstream's per-app list in its bypass mode, the listed apps go around the
 * VPN. Russian banks and state services often refuse foreign addresses, so a preset adds the
 * installed ones in one tap. The VPN takes a changed list on its next start.
 */
object BypassApps {
    /** Checked on RuStore and Google Play on 2026-10-15; only the installed ones are added. */
    val BANKS_AND_STATE = listOf(
        // Banks, investments, payments
        "ru.sberbankmobile", // СберБанк Онлайн
        "ru.sberbank_sbbol", // СберБизнес
        "ru.sberbank.investor", // СберИнвестиции
        "com.idamob.tinkoff.android", // Т-Банк
        "ru.tinkoff.sme", // Т-Бизнес
        "ru.tinkoff.investing", // Т-Инвестиции
        "ru.vtb24.mobilebanking.android", // ВТБ Онлайн
        "ru.vtb.smb", // Бизнес Платформа ВТБ
        "ru.vtb.invest", // ВТБ Мои Инвестиции
        "ru.alfabank.mobile.android", // Альфа-Банк
        "ru.alfabank.oavdo.amc", // Альфа-Бизнес
        "ru.alfadirect.app", // Альфа-Инвестиции
        "ru.gazprombank.android.mobilebank.app", // Газпромбанк
        "ru.raiffeisennews", // Райффайзен Онлайн
        "ru.sovcomcard.halva.v1", // Халва – Совкомбанк
        "ru.sovcombank.investor", // Совкомбанк Инвестиции
        "logo.com.mbanking", // ПСБ
        "ru.rshb.dbo", // Россельхозбанк
        "ru.ozon.fintech.finance", // Ozon Банк
        "ru.wildberries.bank", // WB Банк
        "com.yandex.bank", // Яндекс Пэй
        "ru.yoo.money", // ЮMoney
        "ru.lewis.dbo", // МТС Деньги
        "ru.bspb", // Банк Санкт-Петербург
        "ru.akbars.mobile", // Ак Барс Онлайн
        "ru.mkb.mobile", // МКБ Онлайн
        "ru.bankuralsib.mb.android", // Уралсиб Онлайн
        "ru.homecredit.mycredit", // Хоум Банк
        "cz.bsc.rc", // Ренессанс Банк
        "ru.otpbank.mobile", // ОТП Банк
        "ru.simpls.brs2.mobbank", // Русский Стандарт
        "com.tochka.bank", // Точка
        "com.bank.domrf.v2", // Банк ДОМ.РФ
        "ru.ftc.faktura.absolutbank", // Абсолют Банк
        "ru.zenitonline.android", // Зенит Онлайн
        "ru.skbbank.ib", // Синара Банк
        "ru.avangard", // Банк Авангард
        "ru.centrinvest.mobilebanking2018", // Центр-инвест
        "ru.nspk.mirpay", // Mir Pay
        "ru.nspk.sbpay", // СБПэй
        // State services
        "ru.rostel", // Госуслуги
        "ru.gosuslugi.goskey", // Госключ
        "ru.gosuslugi.auto", // Госуслуги Авто
        "ru.gosuslugi.culture", // Госуслуги Культура
        "ru.sigma.gisgkh", // Госуслуги Дом
        "ru.fns.lkfl", // Налоги ФЛ
        "com.gnivts.selfemployed", // Мой налог
        "ru.gnivc.lkip", // Личный кабинет предпринимателя
        "ru.altarix.mos.pgu", // Госуслуги Москвы
        "ru.mos.app", // Моя Москва
        "ru.mos.myid", // Мой id (mos.ru)
        "ru.rosreestr.mobile", // Росреестр
        "ru.rzd.pass", // РЖД Пассажирам
        "com.octopod.russianpost.client.android", // Почта России
    )

    fun packages(): Set<String> = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toSet().orEmpty()

    /**
     * The advanced mode can turn the list around: only the listed apps go through the VPN. This
     * screen keeps a list of apps without VPN, so it offers to start over in that case.
     */
    val reversed: Boolean
        get() = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false) &&
            !MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false) && packages().isNotEmpty()

    fun save(packages: Set<String>) {
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, packages.toMutableSet())
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, packages.isNotEmpty())
    }

    /** Apps of [preset] installed on the phone and not in [current] yet, in the preset order. */
    fun toAdd(installed: Set<String>, current: Set<String>, preset: List<String> = BANKS_AND_STATE): List<String> =
        preset.filter { it in installed && it !in current }
}
