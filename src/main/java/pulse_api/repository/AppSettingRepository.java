package pulse_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pulse_api.entity.AppSetting;

public interface AppSettingRepository extends JpaRepository<AppSetting, Integer> {
}
