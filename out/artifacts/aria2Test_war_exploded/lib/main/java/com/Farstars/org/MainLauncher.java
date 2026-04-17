//package com.Farstars.org;
//
//import com.Farstars.org.servlet.*;
//import org.eclipse.jetty.server.Server;
//import org.eclipse.jetty.server.handler.HandlerList;
//import org.eclipse.jetty.server.handler.ResourceHandler;
//import org.eclipse.jetty.servlet.ServletContextHandler;
//import org.eclipse.jetty.util.resource.Resource;
//import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
//
//import com.Farstars.org.listener.Aria2ContextListener;
//
//public class MainLauncher {
//    public static void main(String[] args) {
//        int port = 8080;
//        if (args.length > 0) {
//            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
//        }
//
//        Server server = new Server(port);
//
//        // 1. 静态资源处理（从 classpath:/webapp/ 加载 index.html 等）
//        ResourceHandler resourceHandler = new ResourceHandler();
//        resourceHandler.setDirectoriesListed(false);
//        resourceHandler.setWelcomeFiles(new String[]{"index.html"});
//        resourceHandler.setBaseResource(Resource.newClassPathResource("/webapp"));
//
//        // 2. Servlet + WebSocket 上下文
//        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
//        context.setContextPath("/");
//
//        // 手动注册 Servlet
//        context.addServlet(DownloadServlet.class, "/api/download");
//        context.addServlet(PauseServlet.class,"/api/pause");
//        context.addServlet(RemoveServlet.class,"/api/remove");
//        context.addServlet(UnPauseServlet.class,"/api/unpause");
//        context.addServlet(StatusServlet.class, "/api/status");
//
//        // 注册 WebSocket 端点
//        JakartaWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
//            wsContainer.addEndpoint(ProgressWebSocket.class);
//        });
//
//        // 注册 Listener
//        context.addEventListener(new Aria2ContextListener());
//
//        // 3. 组合 Handler：先静态资源，再 Servlet
//        HandlerList handlers = new HandlerList();
//        handlers.addHandler(resourceHandler);
//        handlers.addHandler(context);
//        server.setHandler(handlers);
//
//        try {
//            System.out.println("正在启动 Jetty 服务器 (端口 " + port + ")...");
//            server.start();
//            System.out.println("服务器已启动: http://localhost:" + port + "/");
//            server.join();
//        } catch (Exception ex) {
//            System.err.println("服务器启动失败:");
//            ex.printStackTrace();
//        }
//    }
//}
