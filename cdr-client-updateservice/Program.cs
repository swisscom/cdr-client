using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.EventLog;
using System;
using System.Threading.Tasks;

namespace CuraLineClientUpdateService;

public class Program
{
    public static async Task Main(string[] args)
    {
        var serviceName = "curaLINEClientUpdateService";
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i].Equals("--serviceName", StringComparison.OrdinalIgnoreCase) && i + 1 < args.Length)
            {
                serviceName = args[i + 1];
                break;
            }
        }

        var builder = Host.CreateApplicationBuilder(args);

        builder.Configuration.SetBasePath(AppContext.BaseDirectory);
        builder.Configuration.AddJsonFile("appsettings.json", optional: false, reloadOnChange: true);

        builder.Services.AddSingleton<UpdateService>();
        builder.Services.AddHostedService<UpdateService>(provider => provider.GetService<UpdateService>()!);

        builder.Services.AddWindowsService(options =>
        {
            options.ServiceName = serviceName;
        });

        // Configure logging from appsettings.json
        builder.Logging.ClearProviders();
        builder.Logging.AddConfiguration(builder.Configuration.GetSection("Logging"));
        builder.Logging.AddConsole();
        if (OperatingSystem.IsWindows())
        {
            builder.Logging.AddEventLog(eventLogSettings =>
            {
                eventLogSettings.SourceName = serviceName;
                eventLogSettings.LogName = "Application";
            });
        }

        await builder.Build().RunAsync();
    }
}
