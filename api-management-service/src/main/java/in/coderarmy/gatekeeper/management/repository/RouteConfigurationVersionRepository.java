package in.coderarmy.gatekeeper.management.repository;

import in.coderarmy.gatekeeper.management.entity.RouteConfigurationVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RouteConfigurationVersionRepository extends JpaRepository<RouteConfigurationVersion, Long> {

    List<RouteConfigurationVersion> findAllByOrganizationIdAndRouteIdOrderByVersionNumberDesc(Long organizationId,
                                                                                              String routeId);

    Optional<RouteConfigurationVersion> findTopByOrganizationIdAndRouteIdOrderByVersionNumberDesc(Long organizationId,
                                                                                                  String routeId);

}
