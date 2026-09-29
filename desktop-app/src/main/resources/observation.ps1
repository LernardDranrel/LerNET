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
            Rows (Get-NetFirewallRule -PolicyStore ActiveStore -TracePolicyStore -Enabled True | Select-Object -First 200 | ForEach-Object {
                $rule=$_; $app=Get-NetFirewallApplicationFilter -AssociatedNetFirewallRule $rule
                $port=Get-NetFirewallPortFilter -AssociatedNetFirewallRule $rule
                $addr=Get-NetFirewallAddressFilter -AssociatedNetFirewallRule $rule
                @{Name=$rule.Name;DisplayName=$rule.DisplayName;Direction=[string]$rule.Direction;Action=[string]$rule.Action;Profile=[string]$rule.Profile;PolicyStoreSource=$rule.PolicyStoreSource;PolicyStoreSourceType=[string]$rule.PolicyStoreSourceType;Program=$app.Program;Protocol=$port.Protocol;LocalPort=$port.LocalPort;RemotePort=$port.RemotePort;LocalAddress=$addr.LocalAddress;RemoteAddress=$addr.RemoteAddress}
            })
        }
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
                if ((Get-Item -LiteralPath $file).Length -gt 32MB) { throw 'WFP state exceeds the 32 MB inspection limit' }
                [xml]$state = Get-Content -LiteralPath $file -Raw
                $providers=@{}
                foreach($p in $state.SelectNodes('//providers/item')) { $providers[[string]$p.providerKey]=[string]$p.displayData.name }
                Rows ($state.SelectNodes('//filters/item') | ForEach-Object {
                    @{FilterId=[string]$_.filterId;FilterKey=[string]$_.filterKey;Name=[string]$_.displayData.name;ProviderKey=[string]$_.providerKey;ProviderName=$providers[[string]$_.providerKey];Layer=[string]$_.layerKey;SubLayer=[string]$_.subLayerKey;Action=[string]$_.action.type;Callout=[string]$_.action.calloutKey;Weight=[string]$_.weight;Conditions=@($_.filterCondition.item | ForEach-Object {"$($_.fieldKey) $($_.matchType) $($_.conditionValue.InnerText)"})}
                })
            } finally { Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue }
        }
        default { throw 'Unknown allowlisted source' }
    }
    $rows=@($data)
    @{id=$Source;complete=($partialDetail -eq '' -and $rows.Count -lt 1000 -and -not($Source -eq 'firewall-rules' -and $rows.Count -ge 200));state=$(if($rows.Count -eq 0){'EMPTY'}else{'AVAILABLE'});rows=$rows;detail=$(if($partialDetail){$partialDetail}elseif($Source -eq 'firewall-rules' -and $rows.Count -ge 200){'Показаны первые 200 активных правил; отсутствие правила в этой выборке не означает, что его нет.'}elseif($rows.Count -ge 1000){'Показаны первые 1000 записей; источник может содержать больше.'}else{''})} | ConvertTo-Json -Depth 6 -Compress
} catch {
    $state='ERROR'
    if($_.Exception -is [System.UnauthorizedAccessException] -or $_.CategoryInfo.Category -eq 'PermissionDenied') {$state='ACCESS_DENIED'}
    elseif($_.CategoryInfo.Category -eq 'ObjectNotFound' -or $_.Exception -is [System.Management.Automation.CommandNotFoundException]) {$state='UNSUPPORTED'}
    @{id=$Source;state=$state;rows=@();detail=$_.Exception.Message} | ConvertTo-Json -Depth 4 -Compress
}
