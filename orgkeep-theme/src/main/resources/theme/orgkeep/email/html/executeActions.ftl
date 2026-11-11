<#-- Actions requested by an administrator: the invitation of Orgkeep (UPDATE_PASSWORD: choose a password) -->
<#outputformat "plainText">
<#assign requiredActionsText><#if requiredActions??><#list requiredActions><#items as reqActionItem>${msg("requiredAction.${reqActionItem}")}<#sep>, </#sep></#items></#list></#if></#assign>
</#outputformat>

<#import "template.ftl" as layout>
<@layout.emailLayout>
<#if requiredActions?? && requiredActions?size == 1 && requiredActions[0] == "UPDATE_PASSWORD">
    <@layout.title text=msg("orgkeepInvitationTitle", realmName)/>
    <p style="margin: 0;">${msg("orgkeepInvitationText", realmName)}</p>
    <@layout.button link=link label=msg("orgkeepInvitationButton")/>
<#else>
    <@layout.title text=msg("orgkeepExecuteActionsTitle", realmName)/>
    <p style="margin: 0;">${msg("orgkeepExecuteActionsText", realmName, requiredActionsText)}</p>
    <@layout.button link=link label=msg("orgkeepExecuteActionsButton")/>
</#if>
<@layout.note>${msg("orgkeepLinkExpiration", linkExpirationFormatter(linkExpiration))}</@layout.note>
<@layout.note>${msg("orgkeepExecuteActionsIgnore")}</@layout.note>
</@layout.emailLayout>
