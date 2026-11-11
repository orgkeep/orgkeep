<#--
  Layout of every HTML email: a white card on a light grey page, the logo on top, the realm name at the bottom.
  Built with tables and inline styles, the only layout that holds in Outlook and the webmails.
  The other templates call it with <@layout.emailLayout>, and the emails with a link use <@layout.button>.
-->
<#macro emailLayout>
<html lang="${locale.language}" dir="${(ltr)?then('ltr','rtl')}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <#-- only read by the clients that keep a <style>: the links of the texts of Keycloak, in the colors of Orgkeep -->
    <style>
        a { color: ${properties.primaryColor}; }
    </style>
</head>
<body style="margin: 0; padding: 0; background-color: ${properties.backgroundColor};">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
       style="background-color: ${properties.backgroundColor};">
    <tr>
        <td align="center" style="padding: 32px 16px;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                   style="max-width: 480px; background-color: #ffffff; border: 1px solid ${properties.borderColor}; border-radius: 8px;">
                <#-- url is only there when the email is sent during an HTTP request (always the case for Orgkeep):
                     the logo is then an absolute URL to the resources of this theme. Webmails often block images:
                     the email must read well without it -->
                <#if url?? && url.resourcesUrl??>
                <tr>
                    <td align="center" style="padding: 32px 32px 0 32px;">
                        <img src="${url.resourcesUrl}/img/logo.png" alt="${realmName}" height="80"
                             style="display: block; height: 80px; width: auto; border: 0;">
                    </td>
                </tr>
                </#if>
                <tr>
                    <td style="padding: 24px 32px 32px 32px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; font-size: 15px; line-height: 1.55; color: ${properties.textColor};">
                        <#nested>
                    </td>
                </tr>
            </table>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width: 480px;">
                <tr>
                    <td align="center" style="padding: 16px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; font-size: 12px; color: ${properties.dimmedColor};">
                        ${msg("orgkeepFooter", realmName)}
                    </td>
                </tr>
            </table>
        </td>
    </tr>
</table>
</body>
</html>
</#macro>

<#-- The title of an email -->
<#macro title text>
<h1 style="margin: 0 0 16px 0; font-size: 20px; font-weight: 600; color: ${properties.textColor};">${text}</h1>
</#macro>

<#-- The link of an email as a button (a table cell: the only button that holds in Outlook), then the link in text,
     for the clients that strip it -->
<#macro button link label>
<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin: 24px 0;">
    <tr>
        <td align="center" style="background-color: ${properties.primaryColor}; border-radius: 4px;">
            <a href="${link}" target="_blank"
               style="display: inline-block; padding: 10px 20px; font-size: 14px; font-weight: 600; color: #ffffff; text-decoration: none;">${label}</a>
        </td>
    </tr>
</table>
<p style="margin: 0 0 16px 0; font-size: 12px; color: ${properties.dimmedColor}; word-break: break-all;">
    ${msg("orgkeepLinkFallback")}<br><a href="${link}" style="color: ${properties.primaryColor};">${link}</a>
</p>
</#macro>

<#-- A small dimmed paragraph: expiration, "ignore this email" -->
<#macro note>
<p style="margin: 0 0 8px 0; font-size: 13px; color: ${properties.dimmedColor};"><#nested></p>
</#macro>
