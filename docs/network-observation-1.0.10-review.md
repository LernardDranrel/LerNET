# Ревью «Сеть устройства» 1.0.10

Дата: 30 сентября 2026 года. Область проверки: новый раздел наблюдения Windows/Android, общий анализ, расчёт маршрута, сравнение снимков и жизненный цикл диагностического окна.

Сначала проверены требования: пассивное чтение, явное действие для внешнего IP и записи, отсутствие запуска VPN, сохранение неполных данных без ложных выводов. Затем проверены вызовы API, сериализация, отмена, устаревание результатов и регрессионные тесты. Доступные сейчас официальные документы сопоставлены с Android SDK 36, используемым Windows PowerShell 5.1 и JVM API приложения.

## Исправления

| Область | Ошибка и исправление |
| --- | --- |
| Android API | `NetworkCapabilities.ownerUid` появился в API 30. Проверка `>=29` исправлена; Android 10 больше не вызывает отсутствующий getter. |
| Android маршруты | Для новых Android сохранены реальные типы unreachable/throw. Они не обозначаются обычными маршрутами передачи; метрики, которые Android не сообщает, помечены неизвестными. |
| Android неполные данные | Отсутствующие LinkProperties/Capabilities помечают источники неполными. Потеря доступа отображается в сравнении, а не скрывается. Private DNS без имени при активном режиме описан как opportunistic. |
| Windows IPsec | Отсутствующие поля Protocol/Encryption/Integrity заменены реальными IpProtocol и параметрами первого/второго преобразования. Direction подтверждён как расширенное свойство NetSecurity, а не выдуманное поле CIM. |
| Windows firewall | Для ActiveStore запрашивается TracePolicyStore: происхождение правила может быть заполнено. |
| Windows проекция | Enum и массивы enum сохраняются именами вместо чисел; скрытые адаптеры включены в подробности и счётчики; ошибки чтения разделов установленных программ помечают список неполным. |
| Windows прокси | HKCU явно описан как сохранённые настройки текущего пользователя, WinHTTP — как базовая конфигурация. Они не выдаются за полный расчёт PAC/WPAD и настроек всех приложений. Учётные данные SOCKS/HTTP и текстового WinHTTP скрываются. |
| Общий анализ | Неполный список портов не доказывает отсутствие прокси. IPv4 wildcard не считается доказательством IPv6 listener. Неполные источники отображаются как недостаток данных. |
| Расчёт пути | Недоступная/усечённая таблица и неизвестные метрики не выбирают победителя. Пропущенная метрика не считается известным нулём. IPv4-mapped IPv6 использует путь IPv4. Имя сервера проверяется строгим парсером числовых адресов, без DNS. |
| События Windows | Событие 5157 учитывается только вместе с Security-Auditing. Провайдеры проверяются относительно конкретного журнала. Ограничения 80 событий на журнал/250 событий ETL помечают выборку неполной. |
| Сравнение | Повторная одинаковая недоступность источника не создаёт фиктивных изменений. Реальная смена доступа и полноты остаётся видимой; содержимое неполного источника не используется для вывода об удалении настроек. |
| Жизненный цикл | Обновление/закрытие отменяет проверку IP и исключает поздний результат. Устранена гонка регистрации соединения после закрытия окна. Ответ IP не берётся из кэша. Неожиданный сбой обновления Android сохраняет последний успешный снимок. |
| Отдельная запись | Результат ETW не теряется при обновлении настроек и не сравнивается как изменение пассивного снимка. Новая запись убирает старый результат; после закрытия и исчерпания проверок принадлежности освобождается планировщик. Чужие сессии не останавливаются. |

## Документация

- [Android NetworkCapabilities / ownerUid](https://developer.android.com/reference/android/net/NetworkCapabilities#getOwnerUid()), [LinkProperties / routes](https://developer.android.com/reference/android/net/LinkProperties#getRoutes()), [RouteInfo / type](https://developer.android.com/reference/android/net/RouteInfo#getType()), [Private DNS](https://developer.android.com/reference/android/net/LinkProperties#getPrivateDnsServerName()).
- [ConnectivityManager / getAllNetworks](https://developer.android.com/reference/android/net/ConnectivityManager#getAllNetworks()). Этот deprecated вызов оставлен только для однократного ручного снимка: он не используется как мониторинг или периодический polling. Повторная проверка состава сетей/default network отклоняет снимок при обнаруженной смене; атомарность всех свойств не обещается. Для будущего непрерывного мониторинга нужны callbacks и отдельный контракт накопления данных.
- [MSFT_NetQuickModeSA](https://learn.microsoft.com/en-us/windows/win32/fwp/wmi/wfascimprov/msft-netquickmodesa), [Get-NetFirewallRule](https://learn.microsoft.com/en-us/powershell/module/netsecurity/get-netfirewallrule?view=windowsserver2025-ps), [Get-NetAdapterAdvancedProperty](https://learn.microsoft.com/en-us/powershell/module/netadapter/get-netadapteradvancedproperty?view=windowsserver2025-ps), [Get-NetAdapterStatistics](https://learn.microsoft.com/en-us/powershell/module/netadapter/get-netadapterstatistics?view=windowsserver2025-ps). Дополнительно прочитаны локальные CDXML/type definitions NetSecurity без запуска cmdlets.
- [Get-NetRoute](https://learn.microsoft.com/en-us/powershell/module/nettcpip/get-netroute?view=windowsserver2025-ps), [Winsock dual-stack](https://learn.microsoft.com/en-us/windows/win32/winsock/dual-stack-sockets), [WFP event 5157](https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-10/security/threat-protection/auditing/event-5157).
- [WinHTTP current-user proxy](https://learn.microsoft.com/en-us/windows/win32/api/winhttp/nf-winhttp-winhttpgetieproxyconfigforcurrentuser), [netsh WinHTTP](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/netsh-winhttp).
- [Get-WinEvent, PowerShell 5.1](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.diagnostics/get-winevent?view=powershell-5.1), [MSFT_EtwTraceSession](https://learn.microsoft.com/en-us/previous-versions/windows/desktop/etwmgmt/msft-etwtracesession), [logman create trace](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/logman-create-trace).
- [JVM URLConnection / useCaches](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/URLConnection.html), [HttpURLConnection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/HttpURLConnection.html), [coroutine runInterruptible](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html).

## Проверка и границы

Общий прогон компиляции и тестов прошёл: **99 тестов**, без failures/errors/skips — 39 shared, 44 desktop, 16 Android. После последнего изменения сценария повторно пройдены 43 desktop observation теста. PowerShell AST проверен без выполнения сборщика; отдельные фиксированные проекции проверены на подставных функциях с отключённой автозагрузкой модулей. Тесты не читают реальную конфигурацию сети.

На ноутбуке не запускались LerNET, VPN, реальные сборщики и ETW. Поэтому соответствие реальному набору CIM/модулей Windows и работа записи остаются интеграционными проверками для другого устройства/VM. В частности, сочетание logman `-rf` с `-ets` не признано доказанной гарантией остановки после аварийного завершения приложения: при работающем LerNET остановку выполняет собственный таймер.

Нужные интеграционные сценарии: Windows с обычными/повышенными правами и другим SID; отсутствие модулей/отказ доступа; активный сторонний VPN; IPv4/IPv6 и разные compartments; фактическое имя ETL и остановка собственной сессии; Android 10/11/13+ и смена Wi-Fi во время снимка. Неизвестный результат должен оставаться неизвестным, без сообщения о «мёртвом VPN» по одному источнику.

Имеющиеся предупреждения о миграции AGP/Kotlin и classpath painterResource не являются подтверждёнными сбоями этого раздела. Они остаются отдельной работой по сборочной системе/ресурсам; массовая миграция движка и старого интерфейса в это ревью не включена.
