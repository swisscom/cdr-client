using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.EventLog;
using System;
using System.Threading.Tasks;

namespace CdrClientWatchdog;

public class Program
{
    public static async Task Main(string[] args)
    {
        var builder = Host.CreateApplicationBuilder(args);
        
        builder.Services.AddWindowsService(options =>
        {
            options.ServiceName = "CDRClientWatchdog";
        });

        builder.Services.AddLogging(configure =>
        {
            configure.AddConsole();
            if (OperatingSystem.IsWindows())
            {
                configure.AddEventLog(new EventLogSettings
                {
                    SourceName = "CDRClientWatchdog",
                    LogName = "Application"
                });
            }
        });

        builder.Services.AddSingleton<WatchdogService>();
        builder.Services.AddHostedService<WatchdogService>(provider => provider.GetService<WatchdogService>()!);

        var host = builder.Build();
        await host.RunAsync();
    }
}
