# Read-only, allowlisted Windows sources. No network probes, policy changes or payload capture.
param([string]$Source, [string]$WfpStateFile)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$WarningPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
function Public-Endpoint([string]$value) {
    $safe = $value -replace '(?i)([a-z][a-z0-9+.-]*://)[^/@\s]+@','$1[hidden]@'
    $safe = $safe -replace '[^;=\s/@]+:[^;@/]*@','[hidden]@'
    ($safe -split '[?#]',2)[0]
}
# Convert enum values to their names before JSON (Windows PowerShell otherwise emits numbers).
function Rows($items) {
    @($items | Select-Object -First 1000 | ForEach-Object {
        if ($_ -isnot [System.Collections.IDictionary]) {
            foreach ($property in $_.PSObject.Properties) {
                if ($property.Value -is [Enum]) { $property.Value = [string]$property.Value }
                elseif ($property.Value -is [Array]) {
                    $property.Value = @($property.Value | ForEach-Object { if ($_ -is [Enum]) { [string]$_ } else { $_ } })
                }
            }
        }
        $_
    })
}
function Xml-Field($node, [string]$name) {
    $child = $node.SelectSingleNode("*[local-name()='$name']")
    if ($null -eq $child) { return '' }
    $value = $child.InnerText
    if ($value.Length -gt 4096) { $script:wfpPartial = $true; return $value.Substring(0,4096) }
    return $value
}
# Parse complete provider/filter entries independently. A malformed later section must not
# erase the filters already read; a partial result is explicitly marked and never proves absence.
function Read-WfpState([string]$file) {
    $script:wfpPartial = $false
    $script:wfpDetail = ''
    if ((Get-Item -LiteralPath $file).Length -gt 32MB) { throw 'WFP state exceeds the 32 MB inspection limit' }
    $settings = [System.Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $settings.MaxCharactersInDocument = 32MB
    $reader = [System.Xml.XmlReader]::Create($file, $settings)
    $providers = @{}
    $filters = [Collections.Generic.List[object]]::new()
    $collection = ''
    $collectionDepth = -1
    $seenFilters = $false
    try {
        try {
            while ($reader.Read()) {
                if ($reader.NodeType -eq [Xml.XmlNodeType]::Element -and $reader.LocalName -in @('providers','filters')) {
                    $collection = $reader.LocalName
                    $collectionDepth = $reader.Depth
                    if ($collection -eq 'filters') { $seenFilters = $true }
                } elseif ($reader.NodeType -eq [Xml.XmlNodeType]::EndElement -and $reader.Depth -eq $collectionDepth) {
                    $collection = ''
                } elseif ($reader.NodeType -eq [Xml.XmlNodeType]::Element -and $reader.LocalName -eq 'item' -and $reader.Depth -eq ($collectionDepth + 1) -and $collection) {
                    $subtree = $reader.ReadSubtree()
                    try {
                        $document = [Xml.XmlDocument]::new()
                        $document.XmlResolver = $null
                        $document.Load($subtree)
                        $item = $document.DocumentElement
                        if ($collection -eq 'providers') {
                            $display = $item.SelectSingleNode("*[local-name()='displayData']")
                            $providers[(Xml-Field $item 'providerKey')] = $(if ($display) { Xml-Field $display 'name' } else { '' })
                        } elseif ($filters.Count -lt 1000) { $filters.Add($item) }
                        else { $script:wfpPartial = $true }
                    } finally { $subtree.Dispose() }
                }
            }
            if (-not $seenFilters) { throw 'WFP XML has no filters collection' }
        } catch {
            # No complete filters: retain the failure rather than presenting an empty success.
            if ($filters.Count -eq 0) { throw }
            $script:wfpPartial = $true
            $script:wfpDetail = 'WFP XML прочитан частично: ' + (Read-FailureDetail $_) + '. Сохранены только полностью прочитанные фильтры; отсутствие фильтра не доказывает, что его нет.'
        }
        Rows ($filters | ForEach-Object {
            $filter = $_
            $display = $filter.SelectSingleNode("*[local-name()='displayData']")
            $action = $filter.SelectSingleNode("*[local-name()='action']")
            $key = Xml-Field $filter 'providerKey'
            if ($filter.SelectNodes("*[local-name()='filterCondition']/*[local-name()='item']").Count -gt 32) { $script:wfpPartial = $true }
            @{FilterId=(Xml-Field $filter 'filterId');FilterKey=(Xml-Field $filter 'filterKey');
              Name=$(if ($display) { Xml-Field $display 'name' } else { '' });ProviderKey=$key;ProviderName=$providers[$key];
              Layer=(Xml-Field $filter 'layerKey');SubLayer=(Xml-Field $filter 'subLayerKey');Weight=(Xml-Field $filter 'weight');
              Action=$(if ($action) { Xml-Field $action 'type' } else { '' });Callout=$(if ($action) { Xml-Field $action 'calloutKey' } else { '' });
              Conditions=@($filter.SelectNodes("*[local-name()='filterCondition']/*[local-name()='item']") | Select-Object -First 32 | ForEach-Object {
                  "$(Xml-Field $_ 'fieldKey') $(Xml-Field $_ 'matchType') $(Xml-Field $_ 'conditionValue')"
              })}
        })
    } finally { $reader.Dispose() }
}
function Read-FailureDetail($record) {
    $failure = $record.Exception
    while ($failure.InnerException) { $failure = $failure.InnerException }
    $code = [string]$record.FullyQualifiedErrorId
    if ($code -notmatch '^(System\.[A-Za-z0-9_.]+|MethodInvocationException|InvalidCastToXml|Parameter[A-Za-z]+|UnauthorizedAccessException|AccessDenied)(,[A-Za-z0-9_.]+)?$') { $code = 'UnclassifiedFailure' }
    $detail = $failure.GetType().FullName + '; HRESULT=' + $failure.HResult + '; ' + $code
    if ($failure -is [System.Xml.XmlException]) { $detail += '; line=' + $failure.LineNumber + '; position=' + $failure.LinePosition; if ($failure.Message -match '0x([0-9A-Fa-f]{1,6})') { $detail += '; character=U+' + $Matches[1].ToUpperInvariant() } }
    if ($detail.Length -gt 1024) { $detail = $detail.Substring(0,1024) }
    return $detail
}
# Collect allowlisted sources only. Tests load the pure functions above without running this section.
try {
    $partialDetail = ''
    $data = switch ($Source) {
        'adapters' { Rows (Get-NetAdapter -IncludeHidden | Select-Object InterfaceGuid,ifIndex,Name,InterfaceDescription,Status,Virtual,HardwareInterface,MacAddress,LinkSpeed,DriverInformation,DriverFileName,DriverVersion,NdisVersion) }
        'addresses' { Rows (Get-NetIPAddress -IncludeAllCompartments | Select-Object InterfaceIndex,InterfaceAlias,IPAddress,PrefixLength,AddressFamily,AddressState,PrefixOrigin,SuffixOrigin,CompartmentId) }
        'interfaces' { Rows (Get-NetIPInterface -IncludeAllCompartments | Select-Object InterfaceIndex,InterfaceAlias,AddressFamily,InterfaceMetric,NlMtu,ConnectionState,Dhcp,Forwarding,WeakHostSend,WeakHostReceive,CompartmentId) }
        'routes' { Rows (Get-NetRoute -IncludeAllCompartments -PolicyStore ActiveStore | Select-Object DestinationPrefix,NextHop,InterfaceIndex,InterfaceAlias,RouteMetric,Protocol,State,CompartmentId,@{n='PolicyStore';e={'ActiveStore'}}) }
        'persistent-routes' { Rows (Get-NetRoute -IncludeAllCompartments -PolicyStore PersistentStore | Select-Object DestinationPrefix,NextHop,InterfaceIndex,InterfaceAlias,RouteMetric,Protocol,State,CompartmentId,@{n='PolicyStore';e={'PersistentStore'}}) }
        'dns' { Rows (Get-DnsClientServerAddress | Select-Object InterfaceIndex,InterfaceAlias,AddressFamily,ServerAddresses) }
        'dns-policy' { Rows (Get-DnsClientNrptPolicy -Effective | Select-Object Namespace,NameServers,DnsSecValidationRequired,DirectAccessEnabled,DirectAccessDnsServers,DirectAccessProxyName,DirectAccessProxyType) }
        'dns-suffix' { Rows (Get-DnsClient | Select-Object InterfaceIndex,InterfaceAlias,ConnectionSpecificSuffix,RegisterThisConnectionsAddress,UseSuffixWhenRegistering) }
        'hosts' { Rows (Get-Content -LiteralPath "$env:SystemRoot\System32\drivers\etc\hosts" | Where-Object { $_ -match '^\s*[^#\s]' } | ForEach-Object { @{Entry=(($_ -split '#')[0]).Trim()} }) }
        'user-proxy' {
            $key = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
            $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            @(@{UserSID=$sid;ProxyEnable=$key.ProxyEnable;ProxyServer=(Public-Endpoint $key.ProxyServer);ProxyOverride=$key.ProxyOverride;AutoConfigURL=(Public-Endpoint $key.AutoConfigURL);AutoDetectRegistry=$key.AutoDetect;Scope='Stored current-user registry values; effective per-connection proxy and WPAD are not resolved'})
        }
        'environment-proxy' {
            @('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','NO_PROXY') | ForEach-Object {
                $value=[Environment]::GetEnvironmentVariable($_,'Process')
                if($value){@{Name=$_;Value=(Public-Endpoint $value);Scope='Current process environment'}}
            }
        }
        'installed-apps' {
            $paths=@('HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*','HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*')
            $registryErrors=@()
            $apps=Rows (Get-ItemProperty -Path $paths -ErrorAction SilentlyContinue -ErrorVariable +registryErrors | Where-Object {$_.DisplayName} | Select-Object PSPath,DisplayName,Publisher,DisplayVersion,InstallLocation)
            $readErrors=@($registryErrors | Where-Object {$_.CategoryInfo.Category -ne 'ObjectNotFound'})
            if ($readErrors.Count -gt 0) { $partialDetail='Часть разделов установленных программ недоступна; отсутствие программы в выборке не подтверждает её отсутствие.' }
            if ($apps.Count -eq 0 -and $readErrors.Count -gt 0) { throw $readErrors[0] }
            $apps
        }
        'winhttp-proxy' {
            $text = & "$env:SystemRoot\System32\netsh.exe" winhttp show proxy
            if ($LASTEXITCODE -ne 0) { throw "netsh exit $LASTEXITCODE" }
            @(@{Configuration=(@($text | ForEach-Object { Public-Endpoint $_ }) -join "`n");Scope='Basic WinHTTP proxy configuration; advanced proxy, PAC and WPAD are not evaluated'})
        }
        'listeners' { Rows (Get-NetTCPConnection -State Listen | Select-Object LocalAddress,LocalPort,OwningProcess) }
        'connections' { Rows (Get-NetTCPConnection | Where-Object {$_.State -ne 'Listen'} | Select-Object LocalAddress,LocalPort,RemoteAddress,RemotePort,State,OwningProcess) }
        'udp-endpoints' { Rows (Get-NetUDPEndpoint | Select-Object LocalAddress,LocalPort,OwningProcess) }
        'processes' { Rows (Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,ExecutablePath) }
        'vpn-user' { Rows (Get-VpnConnection | Select-Object Name,ServerAddress,TunnelType,ConnectionStatus,SplitTunneling,AllUserConnection,AuthenticationMethod,EncryptionLevel) }
        'vpn-global' { Rows (Get-VpnConnection -AllUserConnection | Select-Object Name,ServerAddress,TunnelType,ConnectionStatus,SplitTunneling,AllUserConnection,AuthenticationMethod,EncryptionLevel) }
        'network-profiles' { Rows (Get-NetConnectionProfile | Select-Object Name,InterfaceAlias,InterfaceIndex,NetworkCategory,IPv4Connectivity,IPv6Connectivity) }
        'services' { Rows (Get-CimInstance Win32_Service | Where-Object {$_.State -eq 'Running'} | Select-Object Name,DisplayName,ProcessId,StartMode,State) }
        'bindings' { Rows (Get-NetAdapterBinding -Name '*' -IncludeHidden -AllBindings | Select-Object Name,InterfaceDescription,DisplayName,ComponentID,Enabled) }
        'drivers' { Rows (Get-CimInstance Win32_PnPSignedDriver -Filter "DeviceClass='NET'" | Select-Object DeviceName,DeviceID,DriverProviderName,DriverVersion,DriverDate,IsSigned,InfName,Manufacturer) }
        'adapter-properties' { Rows (Get-NetAdapterAdvancedProperty -Name '*' -AllProperties -IncludeHidden | Select-Object Name,DisplayName,DisplayValue,RegistryKeyword,RegistryValue) }
        'adapter-counters' { Rows (Get-NetAdapterStatistics -Name '*' -IncludeHidden | Select-Object Name,ReceivedBytes,SentBytes,ReceivedDiscardedPackets,OutboundDiscardedPackets,ReceivedPacketErrors,OutboundPacketErrors) }
        'neighbors' { Rows (Get-NetNeighbor -IncludeAllCompartments | Select-Object InterfaceIndex,InterfaceAlias,IPAddress,LinkLayerAddress,State,AddressFamily,CompartmentId) }
        'compartments' { Rows (Get-NetCompartment | Select-Object CompartmentId,CompartmentDescription) }
        'firewall-profiles' { Rows (Get-NetFirewallProfile -PolicyStore ActiveStore | Select-Object Name,Enabled,DefaultInboundAction,DefaultOutboundAction,AllowLocalFirewallRules,AllowLocalIPsecRules,PolicyStoreSourceType) }
        'firewall-rules' {
            # Read rule metadata once. Slow per-rule association queries previously discarded
            # this entire source on timeout. Filter collections are independent sources below.
            Rows (Get-NetFirewallRule -PolicyStore ActiveStore -TracePolicyStore -Enabled True | Sort-Object @{Expression={if ([string]$_.Action -eq 'Block') {0} else {1}}},Name | Select-Object Name,InstanceID,DisplayName,Direction,Action,Profile,PolicyStoreSource,PolicyStoreSourceType)
        }
        'firewall-applications' { Rows (Get-NetFirewallApplicationFilter -PolicyStore ActiveStore | Select-Object InstanceID,Program,Package) }
        'firewall-ports' { Rows (Get-NetFirewallPortFilter -PolicyStore ActiveStore | Select-Object InstanceID,Protocol,LocalPort,RemotePort) }
        'firewall-addresses' { Rows (Get-NetFirewallAddressFilter -PolicyStore ActiveStore | Select-Object InstanceID,LocalAddress,RemoteAddress) }
        'ipsec' { Rows (Get-NetIPsecQuickModeSA | Select-Object Name,LocalEndpoint,RemoteEndpoint,LocalPort,RemotePort,IpProtocol,EncapsulationMode,Direction,FirstTransformType,FirstCipherAlgorithm,FirstIntegrityAlgorithm,SecondTransformType,SecondCipherAlgorithm,SecondIntegrityAlgorithm,TransportLayerFilterName) }
        'nat' { Rows (Get-NetNat | Select-Object Name,InternalIPInterfaceAddressPrefix,ExternalIPInterfaceAddressPrefix,Active) }
        'nat-mappings' { Rows (Get-NetNatStaticMapping | Select-Object NatName,Protocol,ExternalIPAddress,ExternalPort,InternalIPAddress,InternalPort,Active) }
        'hyperv-switch' { Rows (Get-VMSwitch | Select-Object Id,Name,SwitchType,NetAdapterInterfaceDescription,AllowManagementOS) }
        'winsock' {
            $text = & "$env:SystemRoot\System32\netsh.exe" winsock show catalog
            if ($LASTEXITCODE -ne 0) { throw "netsh exit $LASTEXITCODE" }
            @(@{Catalog=($text -join "`n")})
        }
        'wlan' {
            $text = & "$env:SystemRoot\System32\netsh.exe" wlan show interfaces
            if ($LASTEXITCODE -ne 0) { throw "netsh exit $LASTEXITCODE" }
            @(@{Interfaces=($text -join "`n");Scope='Read-only; Windows may require location permission for Wi-Fi details'})
        }
        'wfp' {
            $file = $WfpStateFile
            if (-not $file) { throw 'Owned WFP output file is required' }
            try {
                $text = & "$env:SystemRoot\System32\netsh.exe" wfp show state "file=$file"
                if ($LASTEXITCODE -ne 0) { throw "WFP state exit $LASTEXITCODE (requires read permission)" }
                Read-WfpState $file
                if ($script:wfpPartial) { $partialDetail = if ($script:wfpDetail) { $script:wfpDetail } else { 'WFP: часть фильтров, длинных полей или условий сокращена; выборка неполная.' } }
            } finally { Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue }
        }
        default { throw 'Unknown allowlisted source' }
    }
    $rows=@($data)
    @{id=$Source;complete=($partialDetail -eq '' -and $rows.Count -lt 1000);state=$(if($rows.Count -eq 0){'EMPTY'}else{'AVAILABLE'});rows=$rows;detail=$(if($partialDetail){$partialDetail}elseif($rows.Count -ge 1000){'Показаны первые 1000 записей; источник может содержать больше.'}else{''})} | ConvertTo-Json -Depth 6 -Compress
} catch {
    $state='ERROR'
    if($_.Exception -is [System.UnauthorizedAccessException] -or $_.CategoryInfo.Category -eq 'PermissionDenied') {$state='ACCESS_DENIED'}
    elseif($_.CategoryInfo.Category -eq 'ObjectNotFound' -or $_.Exception -is [System.Management.Automation.CommandNotFoundException]) {$state='UNSUPPORTED'}
    # Conversion exceptions used to include the entire WFP XML, exceeding the JSON output limit.
    $detail = Read-FailureDetail $_
    @{id=$Source;state=$state;rows=@();detail=$detail;complete=$false} | ConvertTo-Json -Depth 4 -Compress
}
