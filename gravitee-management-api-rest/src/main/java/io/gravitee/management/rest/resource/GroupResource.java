/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.rest.resource;

import io.gravitee.management.model.GroupEntity;
import io.gravitee.management.model.UpdateGroupEntity;
import io.gravitee.management.model.permissions.RolePermission;
import io.gravitee.management.model.permissions.RolePermissionAction;
import io.gravitee.management.model.permissions.RoleScope;
import io.gravitee.management.rest.security.Permission;
import io.gravitee.management.rest.security.Permissions;
import io.gravitee.management.service.ApiService;
import io.gravitee.management.service.ApplicationService;
import io.gravitee.management.service.GroupService;
import io.gravitee.management.service.exceptions.ForbiddenAccessException;
import io.swagger.annotations.*;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.DELETE;
import javax.ws.rs.*;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import static io.gravitee.common.http.MediaType.APPLICATION_JSON;
import static io.gravitee.management.model.permissions.RolePermissionAction.*;

/**
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com) 
 * @author GraviteeSource Team
 */

@Api(tags = {"Group"})
public class GroupResource extends AbstractResource {

    @Context
    private ResourceContext resourceContext;

    @Inject
    private GroupService groupService;
    @Inject
    private ApiService apiService;
    @Inject
    ApplicationService applicationService;

    @GET
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Get a group")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Group definition", response = GroupEntity.class),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @Permissions({
            @Permission(value = RolePermission.MANAGEMENT_GROUP, acls = RolePermissionAction.READ)
    })
    public GroupEntity get(@PathParam("group") String group) {
        return groupService.findById(group);
    }

    @DELETE
    @ApiOperation(value = "Delete the Group")
    @ApiResponses({
            @ApiResponse(code = 204, message = "Group successfully deleted"),
            @ApiResponse(code = 500, message = "Internal server error")})
    @Permissions({
            @Permission(value = RolePermission.MANAGEMENT_GROUP, acls = RolePermissionAction.DELETE)
    })
    public Response delete(@PathParam("group") String group) {
        checkRights(group);
        groupService.delete(group);
        return Response.noContent().build();
    }

    @PUT
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "Update a group")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Group successfully updated", response = GroupEntity.class),
            @ApiResponse(code = 500, message = "Internal server error")})
    @Permissions({
            @Permission(value = RolePermission.MANAGEMENT_GROUP, acls = RolePermissionAction.UPDATE),
            @Permission(value = RolePermission.GROUP_MEMBER, acls = RolePermissionAction.UPDATE)
    })
    public GroupEntity update(
            @PathParam("group") String group,
            @ApiParam(name = "group", required = true) @Valid @NotNull final UpdateGroupEntity updateGroupEntity) {
        final GroupEntity groupEntity = checkRights(group);
        // check if user is a 'simple group admin' or a platform admin
        if (!permissionService.hasPermission(RolePermission.MANAGEMENT_GROUP, null, CREATE, UPDATE, DELETE)) {
            updateGroupEntity.setMaxInvitation(groupEntity.getMaxInvitation());
            updateGroupEntity.setLockApiRole(groupEntity.isLockApiRole());
            updateGroupEntity.setLockApplicationRole(groupEntity.isLockApplicationRole());
            updateGroupEntity.setSystemInvitation(groupEntity.isSystemInvitation());
            updateGroupEntity.setEmailInvitation(groupEntity.isEmailInvitation());
            updateGroupEntity.setEventRules(groupEntity.getEventRules());
            if (groupEntity.isLockApiRole()) {
                updateGroupEntity.getRoles().put(RoleScope.API, groupEntity.getRoles().get(RoleScope.API));
            }
            if (groupEntity.isLockApplicationRole()) {
                updateGroupEntity.getRoles().put(RoleScope.APPLICATION, groupEntity.getRoles().get(RoleScope.APPLICATION));
            }
        }
        return groupService.update(group, updateGroupEntity);
    }

    @GET
    @Path("/memberships")
    @Produces(APPLICATION_JSON)
    @ApiOperation(value = "get apis or applications linked to this group")
    @Permissions({
            @Permission(value = RolePermission.MANAGEMENT_GROUP, acls = RolePermissionAction.READ)
    })
    public Response getMemberships(
            @PathParam("group")String group,
            @QueryParam("type") String type
            ) {
        if ("api".equalsIgnoreCase(type)) {
            return Response.ok(groupService.getApis(group)).build();

        } else if ("application".equalsIgnoreCase(type)) {
            return Response.ok(groupService.getApplications(group)).build();
        }

        return Response.noContent().build();
    }

    private GroupEntity checkRights(final String group) {
        final GroupEntity groupEntity = get(group);
        if (!groupEntity.isManageable()) {
            throw new ForbiddenAccessException();
        }
        return groupEntity;
    }

    @Path("members")
    public GroupMembersResource groupMembersResource() {
        return resourceContext.getResource(GroupMembersResource.class);
    }

    @Path("invitations")
    public GroupInvitationsResource groupInvitationsResource() {
        return resourceContext.getResource(GroupInvitationsResource.class);
    }
}
